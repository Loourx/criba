package io.github.loourx.criba.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Lee la lista de fotos y vídeos. Solo lectura: aquí no se toca ningún archivo. */
object MediaRepo {

    const val DELETE_DIR = "Criba_para_borrar"
    const val ASIDE_DIR = "Criba_apartado"
    const val PRIMARY_ROOT = "/storage/emulated/0"

    private val PHOTO_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif")
    private val VIDEO_EXT = setOf("mp4", "3gp", "mov", "mkv", "webm")

    /** Carpetas de WhatsApp que se ocultan a la galería con .nomedia. */
    private val WA_BASES = listOf(
        "Android/media/com.whatsapp/WhatsApp/Media",
        "Android/media/com.whatsapp.w4b/WhatsApp Business/Media",
        "WhatsApp/Media",
    )
    private val WA_DIRS = linkedMapOf(
        "WhatsApp Video/Sent" to "WhatsApp · vídeos enviados",
        "WhatsApp Video/Private" to "WhatsApp · vídeos privados",
        "WhatsApp Images/Sent" to "WhatsApp · fotos enviadas",
        "WhatsApp Images/Private" to "WhatsApp · fotos privadas",
    )

    /** Solo las carpetas de revisión de la raíz de cada almacenamiento, no otras con el mismo nombre. */
    private val CRIBA_ROOT_DIR =
        Regex("^/storage/(emulated/[0-9]+|[^/]+)/(" + DELETE_DIR + "|" + ASIDE_DIR + ")/")

    fun isInCribaDir(path: String): Boolean = CRIBA_ROOT_DIR.containsMatchIn(path)

    fun isVideoName(name: String) = name.substringAfterLast('.', "").lowercase() in VIDEO_EXT

    fun load(context: Context): List<Item> {
        val out = ArrayList<Item>(40_000)
        val seen = HashSet<String>()
        queryMediaStore(context, out, seen)
        listWhatsApp(out, seen)
        return out
    }

    private fun queryMediaStore(context: Context, out: MutableList<Item>, seen: MutableSet<String>) {
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DURATION,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.VOLUME_NAME,
        )
        val type = MediaStore.Files.FileColumns.MEDIA_TYPE
        val selection = "($type=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR " +
            "$type=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"

        context.contentResolver.query(uri, projection, selection, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(projection[0])
            val iData = c.getColumnIndexOrThrow(projection[1])
            val iName = c.getColumnIndexOrThrow(projection[2])
            val iBucket = c.getColumnIndexOrThrow(projection[3])
            val iMime = c.getColumnIndexOrThrow(projection[4])
            val iSize = c.getColumnIndexOrThrow(projection[5])
            val iTaken = c.getColumnIndexOrThrow(projection[6])
            val iModified = c.getColumnIndexOrThrow(projection[7])
            val iDuration = c.getColumnIndexOrThrow(projection[8])
            val iWidth = c.getColumnIndexOrThrow(projection[9])
            val iHeight = c.getColumnIndexOrThrow(projection[10])
            val iType = c.getColumnIndexOrThrow(projection[11])
            val iVolume = c.getColumnIndexOrThrow(projection[12])
            while (c.moveToNext()) {
                val path = c.getString(iData) ?: continue
                if (isInCribaDir(path)) continue
                val rowId = c.getLong(iId)
                val volume = c.getString(iVolume) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                val isVideo = c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val collection = if (isVideo) MediaStore.Video.Media.getContentUri(volume)
                                 else MediaStore.Images.Media.getContentUri(volume)
                var size = c.getLong(iSize)
                if (size <= 0) size = File(path).length()
                val taken = c.getLong(iTaken)
                out += Item(
                    id = "m$volume:$rowId",
                    path = path,
                    name = c.getString(iName) ?: path.substringAfterLast('/'),
                    album = c.getString(iBucket)?.takeIf { it.isNotBlank() } ?: "(sin álbum)",
                    isVideo = isVideo,
                    mime = c.getString(iMime) ?: "",
                    size = size,
                    dateMs = if (taken > 0) taken else c.getLong(iModified) * 1000,
                    durationMs = if (c.isNull(iDuration)) 0 else c.getLong(iDuration),
                    width = c.getInt(iWidth),
                    height = c.getInt(iHeight),
                    whatsapp = false,
                    mediaUri = ContentUris.withAppendedId(collection, rowId).toString(),
                )
                seen += path
            }
        }
    }

    private fun listWhatsApp(out: MutableList<Item>, seen: MutableSet<String>) {
        for (base in WA_BASES) {
            for ((dir, album) in WA_DIRS) {
                val files = File("$PRIMARY_ROOT/$base/$dir").listFiles() ?: continue
                for (f in files) {
                    val ext = f.extension.lowercase()
                    val isVideo = ext in VIDEO_EXT
                    if (!isVideo && ext !in PHOTO_EXT) continue
                    // Una sola llamada al sistema por archivo para tipo, tamaño y fecha.
                    val attrs = runCatching {
                        Files.readAttributes(f.toPath(), BasicFileAttributes::class.java)
                    }.getOrNull() ?: continue
                    if (!attrs.isRegularFile) continue
                    val path = f.absolutePath
                    if (!seen.add(path)) continue
                    out += Item(
                        id = "w" + sha1(path).take(16),
                        path = path,
                        name = f.name,
                        album = album,
                        isVideo = isVideo,
                        mime = if (isVideo) "video/$ext" else "image/$ext",
                        size = attrs.size(),
                        dateMs = attrs.lastModifiedTime().toMillis(),
                        durationMs = 0,
                        width = 0,
                        height = 0,
                        whatsapp = true,
                        mediaUri = null,
                    )
                }
            }
        }
    }

    /** Construye un elemento a partir de un archivo ya movido (para poder devolverlo). */
    fun itemFromFile(id: String, path: String): Item? {
        val f = File(path)
        if (!f.isFile) return null
        val isVideo = isVideoName(f.name)
        return Item(
            id = id, path = path, name = f.name, album = f.parentFile?.name ?: "",
            isVideo = isVideo, mime = if (isVideo) "video/*" else "image/*", size = f.length(),
            dateMs = f.lastModified(), durationMs = 0, width = 0, height = 0,
            whatsapp = false, mediaUri = null,
        )
    }

    fun likeEscape(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private val HEX = "0123456789abcdef".toCharArray()

    private fun sha1(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        val out = CharArray(d.size * 2)
        for (i in d.indices) {
            val v = d[i].toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }
}

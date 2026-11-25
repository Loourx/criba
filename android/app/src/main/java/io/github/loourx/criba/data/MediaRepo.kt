package io.github.loourx.criba.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import java.io.File
import java.security.MessageDigest

/** Lee la lista de fotos y vídeos. Solo lectura: aquí no se toca ningún archivo. */
object MediaRepo {

    const val DELETE_DIR = "Criba_para_borrar"
    const val ASIDE_DIR = "Criba_apartado"

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

    const val PRIMARY_ROOT = "/storage/emulated/0"

    /** Raíz del almacenamiento donde está [path]: memoria interna o tarjeta SD. */
    fun volumeRoot(path: String): String {
        if (path.startsWith("$PRIMARY_ROOT/")) return PRIMARY_ROOT
        val parts = path.split('/')
        // /storage/XXXX-XXXX/...
        return if (parts.size > 3 && parts[1] == "storage") "/storage/${parts[2]}" else PRIMARY_ROOT
    }

    fun isInCribaDir(path: String): Boolean =
        path.contains("/$DELETE_DIR/") || path.contains("/$ASIDE_DIR/")

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
        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}" +
            " OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})" +
            " AND ${MediaStore.Files.FileColumns.DATA} NOT LIKE ? ESCAPE '\\'" +
            " AND ${MediaStore.Files.FileColumns.DATA} NOT LIKE ? ESCAPE '\\'"
        val args = arrayOf("%/${likeEscape(DELETE_DIR)}/%", "%/${likeEscape(ASIDE_DIR)}/%")

        context.contentResolver.query(uri, projection, selection, args, null)?.use { c ->
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
                val folder = File("$PRIMARY_ROOT/$base/$dir")
                val files = folder.listFiles() ?: continue
                for (f in files) {
                    if (!f.isFile) continue
                    val ext = f.extension.lowercase()
                    val isVideo = ext in VIDEO_EXT
                    if (!isVideo && ext !in PHOTO_EXT) continue
                    val path = f.absolutePath
                    if (!seen.add(path)) continue
                    out += Item(
                        id = "w" + sha1(path).take(16),
                        path = path,
                        name = f.name,
                        album = album,
                        isVideo = isVideo,
                        mime = if (isVideo) "video/$ext" else "image/$ext",
                        size = f.length(),
                        dateMs = f.lastModified(),
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

    fun likeEscape(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

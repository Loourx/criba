package io.github.loourx.criba.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.MediaStore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import kotlin.coroutines.resume

/**
 * Mueve archivos dentro del mismo almacenamiento. Nunca borra y nunca sobrescribe.
 *
 * Cada movimiento se apunta en un diario antes de hacerse. Si Android cierra la app a mitad,
 * al volver a abrirla [reconcile] mira en el disco qué pasó y deja las decisiones en orden.
 */
class Mover(private val context: Context, private val decisions: Decisions) {

    private val journal = File(context.filesDir, "diario.jsonl")
    private val storage = context.getSystemService(StorageManager::class.java)

    /** Raíces de los almacenamientos montados (memoria interna y tarjetas SD). */
    fun roots(): List<String> =
        storage.storageVolumes.mapNotNull { it.directory?.absolutePath }.distinct()

    /** Raíz del almacenamiento donde está [path], según Android. */
    private fun rootOf(path: String): String? =
        runCatching { storage.getStorageVolume(File(path))?.directory?.absolutePath }.getOrNull()

    /**
     * Mueve [item] a la carpeta de revisión que le toca. No avisa a la galería: quien llama
     * junta las rutas y llama a [scan] una vez al final. Devuelve (ruta vieja, ruta nueva).
     */
    fun apply(item: Item, choice: Choice): Result<Pair<String, String>> {
        val dirName = when (choice) {
            Choice.DELETE -> MediaRepo.DELETE_DIR
            Choice.SET_ASIDE -> MediaRepo.ASIDE_DIR
            else -> return Result.failure(IllegalArgumentException("Nada que mover."))
        }
        val src = File(item.path)
        if (!src.exists()) return Result.failure(IllegalStateException("El archivo ya no está en el móvil."))
        // Mismo almacenamiento que el original: mover entre memoria interna y tarjeta SD exigiría copiar.
        val root = rootOf(item.path) ?: return Result.failure(IllegalStateException("No se reconoce el almacenamiento."))
        val folder = File("$root/$dirName/${safeFolder(item.album)}")
        val dst = moveNoOverwrite(item.id, src, folder).getOrElse { return Result.failure(it) }
        try {
            decisions.markApplied(item.id, choice,
                Applied(src.absolutePath, dst.absolutePath, System.currentTimeMillis(), item.size))
        } catch (e: Exception) {
            // El archivo ya se movió y el diario lo tiene apuntado: al reabrir la app se recupera.
            return Result.failure(IllegalStateException("Movido, pero no se pudo apuntar. ¿Móvil sin espacio?"))
        }
        writeJournal(item.id, "fin", src.absolutePath, dst.absolutePath)
        return Result.success(src.absolutePath to dst.absolutePath)
    }

    /** Devuelve un archivo movido a su carpeta original, sin pisar nada. Devuelve (vieja, nueva). */
    fun restore(id: String): Result<Pair<String, String>> {
        val applied = decisions.get(id)?.applied ?: return Result.failure(IllegalStateException("No estaba movido."))
        val now = File(applied.now)
        if (!now.exists()) return Result.failure(IllegalStateException("Ya no está en la carpeta de revisión."))
        val original = File(applied.before)
        val parent = original.parentFile ?: return Result.failure(IllegalStateException("Ruta original no válida."))
        val parentExisted = parent.isDirectory
        val back = moveNoOverwrite(id, now, parent, preferredName = original.name).getOrElse { return Result.failure(it) }
        // Si la carpeta de WhatsApp ya no existía, se recrea oculta a la galería como estaba.
        if (!parentExisted && WA_HIDDEN.containsMatchIn(parent.absolutePath)) {
            runCatching { File(parent, ".nomedia").createNewFile() }
        }
        decisions.forget(id)
        writeJournal(id, "devuelto", now.absolutePath, back.absolutePath)
        return Result.success(now.absolutePath to back.absolutePath)
    }

    private fun moveNoOverwrite(id: String, src: File, folder: File, preferredName: String = src.name): Result<File> {
        if (!folder.isDirectory && !folder.mkdirs()) {
            return Result.failure(IllegalStateException("No se pudo crear la carpeta ${folder.name}."))
        }
        val dot = preferredName.lastIndexOf('.')
        val base = if (dot > 0) preferredName.substring(0, dot) else preferredName
        val ext = if (dot > 0) preferredName.substring(dot) else ""
        for (n in 0 until 1000) {
            val dst = File(folder, if (n == 0) preferredName else "$base ($n)$ext")
            if (dst.exists()) continue
            writeJournal(id, "inicio", src.absolutePath, dst.absolutePath)
            try {
                // Sin REPLACE_EXISTING: si alguien creó el archivo justo ahora, falla en vez de pisarlo.
                Files.move(src.toPath(), dst.toPath())
                return Result.success(dst)
            } catch (e: FileAlreadyExistsException) {
                continue
            } catch (e: Exception) {
                writeJournal(id, "fallo", src.absolutePath, dst.absolutePath)
                return Result.failure(IllegalStateException("El móvil no dejó mover el archivo (${e.javaClass.simpleName})."))
            }
        }
        return Result.failure(IllegalStateException("Demasiados archivos con el mismo nombre."))
    }

    /**
     * Al abrir la app: termina de apuntar los movimientos que quedaron a medias.
     * Lo que no sabe resolver lo deja en el diario, no lo borra.
     */
    fun reconcile() {
        try {
            reconcileNow()
        } catch (e: Exception) {
            // Sin espacio u otro error de disco: el diario se queda como está y se reintenta al reabrir.
        }
    }

    private fun reconcileNow() {
        if (!journal.exists()) return
        val open = LinkedHashMap<String, JSONObject>()
        journal.forEachLine { line ->
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
            val id = o.optString("id")
            if (o.optString("paso") == "inicio") open[id] = o else open.remove(id)
        }
        val unresolved = ArrayList<String>()
        val toScan = ArrayList<String>()
        for ((id, o) in open) {
            val src = File(o.getString("de"))
            val dst = File(o.getString("a"))
            when {
                // no llegó a moverse: no hay nada que apuntar
                src.exists() && !dst.exists() -> Unit
                dst.exists() && !src.exists() -> {
                    val d = decisions.get(id)
                    when {
                        d?.applied != null && d.applied.now == dst.absolutePath -> Unit // ya apuntado
                        d?.applied != null && d.applied.now == src.absolutePath -> decisions.forget(id) // devolución hecha
                        d?.applied == null -> {
                            // Movimiento hecho pero sin apuntar: la carpeta de destino dice qué era.
                            val choice = d?.choice?.takeIf { it == Choice.DELETE || it == Choice.SET_ASIDE }
                                ?: if (ASIDE_ROOT.containsMatchIn(dst.absolutePath)) Choice.SET_ASIDE else Choice.DELETE
                            decisions.markApplied(id, choice, Applied(src.absolutePath, dst.absolutePath,
                                System.currentTimeMillis(), dst.length()))
                        }
                        else -> unresolved += o.toString()
                    }
                    toScan += src.absolutePath; toScan += dst.absolutePath
                }
                else -> unresolved += o.toString()
            }
        }
        if (toScan.isNotEmpty()) MediaScannerConnection.scanFile(context, toScan.toTypedArray(), null, null)
        if (unresolved.isEmpty()) journal.delete()
        else journal.writeText(unresolved.joinToString("\n", postfix = "\n"))
    }

    @Synchronized
    private fun writeJournal(id: String, step: String, from: String, to: String) {
        runCatching {
            journal.appendText(
                JSONObject().put("id", id).put("paso", step).put("de", from).put("a", to)
                    .put("t", System.currentTimeMillis()).toString() + "\n"
            )
        }
    }

    /** Avisa a la galería de los cambios, por partes, y espera a que termine. */
    suspend fun scan(paths: List<String>) {
        for (chunk in paths.distinct().chunked(300)) {
            withTimeoutOrNull(10_000L + chunk.size * 100L) {
                suspendCancellableCoroutine { cont ->
                    var left = chunk.size
                    val lock = Any()
                    MediaScannerConnection.scanFile(context, chunk.toTypedArray(), null) { _, _ ->
                        synchronized(lock) {
                            left--
                            if (left == 0 && cont.isActive) cont.resume(Unit)
                        }
                    }
                }
            }
        }
    }

    /** Todos los archivos que hay ahora en Criba_para_borrar, en la raíz de cada almacenamiento. */
    fun filesToEmpty(): List<File> = roots().flatMap { root ->
        File("$root/${MediaRepo.DELETE_DIR}").walkTopDown().filter { it.isFile }.toList()
    }

    /**
     * Prepara el vaciado: indexa lo que haya en Criba_para_borrar y devuelve sus direcciones en la
     * galería, para pedir a Android que las borre. El borrado lo confirma el usuario en el diálogo
     * del sistema; la app no borra nada por su cuenta.
     */
    suspend fun urisToEmpty(): List<Uri> {
        scan(filesToEmpty().map { it.absolutePath })
        val uris = ArrayList<Uri>()
        val type = MediaStore.Files.FileColumns.MEDIA_TYPE
        val projection = arrayOf(MediaStore.Files.FileColumns._ID, type, MediaStore.Files.FileColumns.VOLUME_NAME)
        val selection = "${MediaStore.Files.FileColumns.DATA} LIKE ? ESCAPE '\\' AND " +
            "($type=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR $type=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        for (root in roots()) {
            // Solo <raíz>/Criba_para_borrar/…, nunca otra carpeta que se llame igual más adentro.
            val pattern = MediaRepo.likeEscape("$root/${MediaRepo.DELETE_DIR}/") + "%"
            context.contentResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, selection, arrayOf(pattern), null
            )?.use { c ->
                while (c.moveToNext()) {
                    val volume = c.getString(2) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                    val collection = if (c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
                        MediaStore.Video.Media.getContentUri(volume) else MediaStore.Images.Media.getContentUri(volume)
                    uris += ContentUris.withAppendedId(collection, c.getLong(0))
                }
            }
        }
        return uris
    }

    companion object {
        private val ASIDE_ROOT = Regex("^/storage/(emulated/[0-9]+|[^/]+)/" + MediaRepo.ASIDE_DIR + "/")
        private val WA_HIDDEN = Regex("/WhatsApp( Business)?/Media/WhatsApp (Video|Images)/(Sent|Private)$")

        /** Nombre de carpeta seguro a partir del nombre del álbum. */
        fun safeFolder(album: String): String {
            var s = album.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trimStart('.', ' ').trim()
            if (s.isEmpty()) s = "Sin álbum"
            // Como mucho 120 bytes (el sistema admite 255) y sin partir un carácter por la mitad.
            while (s.toByteArray(Charsets.UTF_8).size > 120) s = s.substring(0, s.offsetByCodePoints(0, s.codePointCount(0, s.length) - 1))
            return s.trimEnd('.', ' ').ifEmpty { "Sin álbum" }
        }
    }
}

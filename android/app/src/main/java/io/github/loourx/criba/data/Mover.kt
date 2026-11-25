package io.github.loourx.criba.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
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

    /** Mueve [item] a la carpeta de revisión que le toca. Devuelve la ruta nueva o un error legible. */
    suspend fun apply(item: Item, choice: Choice): Result<String> {
        val dirName = when (choice) {
            Choice.DELETE -> MediaRepo.DELETE_DIR
            Choice.SET_ASIDE -> MediaRepo.ASIDE_DIR
            else -> return Result.failure(IllegalArgumentException("Nada que mover"))
        }
        val src = File(item.path)
        if (!src.exists()) return Result.failure(IllegalStateException("El archivo ya no está en el móvil."))
        // Mismo almacenamiento que el original: mover entre memoria interna y tarjeta SD no es posible sin copiar.
        val folder = File("${MediaRepo.volumeRoot(item.path)}/$dirName/${safeFolder(item.album)}")
        val dst = moveNoOverwrite(item.id, src, folder).getOrElse { return Result.failure(it) }
        decisions.markApplied(item.id, Applied(src.absolutePath, dst.absolutePath, System.currentTimeMillis()))
        writeJournal(item.id, "fin", src.absolutePath, dst.absolutePath)
        scan(listOf(src.absolutePath, dst.absolutePath))
        return Result.success(dst.absolutePath)
    }

    /** Devuelve un archivo movido a su carpeta original, sin pisar nada que haya allí ahora. */
    suspend fun restore(id: String): Result<String> {
        val applied = decisions.get(id)?.applied ?: return Result.failure(IllegalStateException("No estaba movido."))
        val now = File(applied.now)
        if (!now.exists()) return Result.failure(IllegalStateException("Ya no está en la carpeta de revisión."))
        val original = File(applied.before)
        val back = moveNoOverwrite(id, now, original.parentFile ?: return Result.failure(IllegalStateException("Ruta original no válida.")),
            preferredName = original.name).getOrElse { return Result.failure(it) }
        decisions.forget(id)
        writeJournal(id, "devuelto", now.absolutePath, back.absolutePath)
        scan(listOf(now.absolutePath, back.absolutePath))
        return Result.success(back.absolutePath)
    }

    private fun moveNoOverwrite(id: String, src: File, folder: File, preferredName: String = src.name): Result<File> {
        if (!folder.isDirectory && !folder.mkdirs()) {
            return Result.failure(IllegalStateException("No se pudo crear la carpeta ${folder.name}."))
        }
        val base = preferredName.substringBeforeLast('.', preferredName)
        val ext = preferredName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 0
        while (n < 1000) {
            val dst = File(folder, if (n == 0) preferredName else "$base ($n)$ext")
            n++
            if (dst.exists()) continue
            writeJournal(id, "inicio", src.absolutePath, dst.absolutePath)
            return try {
                // Sin REPLACE_EXISTING: si alguien creó el archivo justo ahora, falla en vez de pisarlo.
                Files.move(src.toPath(), dst.toPath())
                Result.success(dst)
            } catch (e: java.nio.file.FileAlreadyExistsException) {
                continue
            } catch (e: Exception) {
                Result.failure(IllegalStateException("El móvil no dejó mover el archivo (${e.javaClass.simpleName})."))
            }
        }
        return Result.failure(IllegalStateException("Demasiados archivos con el mismo nombre."))
    }

    /** Al abrir la app: termina de apuntar los movimientos que quedaron a medias. */
    fun reconcile() {
        if (!journal.exists()) return
        val open = LinkedHashMap<String, JSONObject>()
        journal.forEachLine { line ->
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
            val id = o.optString("id")
            when (o.optString("paso")) {
                "inicio" -> open[id] = o
                else -> open.remove(id)
            }
        }
        for ((id, o) in open) {
            val src = File(o.getString("de"))
            val dst = File(o.getString("a"))
            if (dst.exists() && !src.exists()) {
                val d = decisions.get(id)
                if (d != null && d.applied == null) {
                    decisions.markApplied(id, Applied(src.absolutePath, dst.absolutePath, System.currentTimeMillis()))
                } else if (d?.applied != null && d.applied.now == src.absolutePath) {
                    decisions.forget(id) // era una devolución que llegó a hacerse
                }
                MediaScannerConnection.scanFile(context, arrayOf(src.absolutePath, dst.absolutePath), null, null)
            }
        }
        journal.delete()
    }

    private fun writeJournal(id: String, step: String, from: String, to: String) {
        journal.appendText(
            JSONObject().put("id", id).put("paso", step).put("de", from).put("a", to)
                .put("t", System.currentTimeMillis()).toString() + "\n"
        )
    }

    /** Avisa a la galería de los cambios y espera a que termine (como mucho 20 s). */
    suspend fun scan(paths: List<String>) {
        if (paths.isEmpty()) return
        withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine { cont ->
                var left = paths.size
                MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ ->
                    synchronized(this@Mover) {
                        left--
                        if (left == 0 && cont.isActive) cont.resume(Unit)
                    }
                }
            }
        }
    }

    /** Todos los archivos que hay ahora en Criba_para_borrar (memoria interna y tarjetas SD). */
    fun filesToEmpty(): List<File> {
        val roots = (listOf(MediaRepo.PRIMARY_ROOT) + (File("/storage").listFiles()
            ?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" }
            ?.map { it.absolutePath } ?: emptyList())).distinct()
        return roots.flatMap { root ->
            File("$root/${MediaRepo.DELETE_DIR}").walkTopDown().filter { it.isFile }.toList()
        }
    }

    /**
     * Prepara el vaciado: indexa lo que haya en Criba_para_borrar y devuelve sus direcciones en la
     * galería, para pedir a Android que las borre. El borrado lo confirma el usuario en el diálogo
     * del sistema; la app no borra nada por su cuenta.
     */
    suspend fun urisToEmpty(): List<Uri> {
        val files = filesToEmpty()
        scan(files.map { it.absolutePath })
        val uris = ArrayList<Uri>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.VOLUME_NAME,
        )
        val selection = "${MediaStore.Files.FileColumns.DATA} LIKE ? ESCAPE '\\' AND " +
            "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        context.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), projection, selection,
            arrayOf("%/${MediaRepo.likeEscape(MediaRepo.DELETE_DIR)}/%"), null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val volume = c.getString(2) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                val collection = if (c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
                    MediaStore.Video.Media.getContentUri(volume) else MediaStore.Images.Media.getContentUri(volume)
                uris += ContentUris.withAppendedId(collection, id)
            }
        }
        return uris
    }

    companion object {
        /** Nombre de carpeta seguro a partir del nombre del álbum. */
        fun safeFolder(album: String): String {
            var s = album.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trimStart('.')
            if (s.isEmpty() || s == "." || s == "..") s = "Sin álbum"
            return s.take(80)
        }
    }
}

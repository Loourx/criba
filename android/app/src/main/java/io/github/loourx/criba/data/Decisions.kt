package io.github.loourx.criba.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Decisiones del usuario, en memoria y en un diario en disco (una línea por cambio).
 *
 * - Cada cambio añade una línea; nunca se reescribe todo el archivo en cada gesto.
 * - Las escrituras van en orden por un único hilo. [markApplied] espera a que su línea esté en
 *   disco: el registro de a dónde se movió un archivo no se puede perder.
 * - Al cargar, una línea dañada se ignora sola; no arrastra al resto.
 */
class Decisions(context: Context) {
    private val file = File(context.filesDir, "decisiones.jsonl")
    private val log = File(context.filesDir, "registro.jsonl")
    private val map = ConcurrentHashMap<String, Decision>()
    private val writer = Executors.newSingleThreadExecutor()

    /** Último error de escritura (por ejemplo, sin espacio), para avisar al usuario. */
    @Volatile var writeError: String? = null
        private set

    /** Lee el diario en el hilo de escritura, para que no se cruce con escrituras pendientes. */
    fun load() {
        writer.submit { loadNow() }.get()
    }

    private fun loadNow() {
        map.clear()
        if (!file.exists()) return
        var lines = 0
        file.forEachLine { line ->
            lines++
            try {
                val o = JSONObject(line)
                val id = o.getString("id")
                if (o.optBoolean("x")) { map.remove(id); return@forEachLine }
                val choice = Choice.valueOf(o.getString("c"))
                val applied = o.optJSONObject("a")?.let {
                    Applied(it.getString("b"), it.getString("n"), it.optLong("t"), it.optLong("s"))
                }
                map[id] = Decision(choice, applied)
            } catch (_: Exception) {
                // línea incompleta o dañada: se salta
            }
        }
        if (lines > map.size * 2 + 200) {
            try { compact() } catch (e: Exception) { writeError = "No se pudo ordenar el archivo de decisiones." }
        }
    }

    fun get(id: String): Decision? = map[id]
    fun contains(id: String): Boolean = map.containsKey(id)
    fun entries(): Set<Map.Entry<String, Decision>> = map.entries

    fun set(ids: Collection<String>, choice: Choice?) {
        val lines = ArrayList<String>(ids.size)
        for (id in ids) {
            if (map[id]?.applied != null) continue // ya movido: solo cambia devolviéndolo
            if (choice == null) {
                map.remove(id)
                lines += JSONObject().put("id", id).put("x", true).toString()
            } else {
                map[id] = Decision(choice)
                lines += JSONObject().put("id", id).put("c", choice.name).toString()
            }
        }
        if (lines.isNotEmpty()) writer.execute { safeAppend(lines) }
    }

    /**
     * Apunta que un archivo se movió. Vuelve cuando está escrito en disco.
     * Lanza una excepción si no se pudo escribir (por ejemplo, sin espacio).
     */
    fun markApplied(id: String, choice: Choice, applied: Applied) {
        map[id] = Decision(choice, applied)
        val line = JSONObject().put("id", id).put("c", choice.name).put("a", appliedJson(applied)).toString()
        val reg = JSONObject().put("t", applied.atMs).put("id", id).put("accion", choice.name)
            .put("antes", applied.before).put("ahora", applied.now).toString()
        writer.submit {
            append(listOf(line))
            log.appendText(reg + "\n")
        }.get()
    }

    /** Olvida la decisión (tras devolver el archivo a su sitio o si ya no existe). */
    fun forget(id: String) {
        if (map.remove(id) != null) {
            val line = JSONObject().put("id", id).put("x", true).toString()
            writer.submit { safeAppend(listOf(line)) }.get()
        }
    }

    private fun appliedJson(a: Applied) =
        JSONObject().put("b", a.before).put("n", a.now).put("t", a.atMs).put("s", a.size)

    private fun append(lines: List<String>) {
        file.appendText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun safeAppend(lines: List<String>) {
        try {
            append(lines)
        } catch (e: Exception) {
            writeError = "No se pudieron guardar las decisiones. ¿Está el móvil sin espacio?"
        }
    }

    /** Reescribe el diario solo con el estado actual (al cargar, si ha crecido mucho). */
    private fun compact() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.bufferedWriter().use { w ->
            for ((id, d) in map) {
                val o = JSONObject().put("id", id).put("c", d.choice.name)
                d.applied?.let { o.put("a", appliedJson(it)) }
                w.write(o.toString()); w.newLine()
            }
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

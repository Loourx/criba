package io.github.loourx.criba.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Decisiones guardadas en un JSON dentro de la app. Se escribe en un archivo temporal y se
 * renombra, para que un cierre a mitad de escritura nunca deje el archivo roto.
 */
class Decisions(context: Context) {
    private val file = File(context.filesDir, "decisiones.json")
    private val log = File(context.filesDir, "registro.jsonl")
    private val map = LinkedHashMap<String, Decision>()

    init {
        load()
    }

    @Synchronized
    fun get(id: String): Decision? = map[id]

    @Synchronized
    fun all(): Map<String, Decision> = HashMap(map)

    @Synchronized
    fun set(ids: Collection<String>, choice: Choice?) {
        for (id in ids) {
            val cur = map[id]
            if (cur?.applied != null) continue // ya movido: solo se cambia devolviéndolo
            if (choice == null) map.remove(id) else map[id] = Decision(choice)
        }
        save()
    }

    @Synchronized
    fun markApplied(id: String, applied: Applied) {
        val cur = map[id] ?: return
        map[id] = cur.copy(applied = applied)
        save()
        log.appendText(
            JSONObject()
                .put("t", applied.atMs).put("id", id).put("accion", cur.choice.name)
                .put("antes", applied.before).put("ahora", applied.now)
                .toString() + "\n"
        )
    }

    /** Tras devolver un archivo a su sitio, se olvida la decisión. */
    @Synchronized
    fun forget(id: String) {
        map.remove(id)
        save()
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val o = JSONObject(file.readText())
            for (k in o.keys()) {
                val d = o.getJSONObject(k)
                val choice = runCatching { Choice.valueOf(d.getString("c")) }.getOrNull() ?: continue
                val applied = d.optJSONObject("a")?.let {
                    Applied(it.getString("b"), it.getString("n"), it.optLong("t"))
                }
                map[k] = Decision(choice, applied)
            }
        }
    }

    private fun save() {
        val o = JSONObject()
        for ((k, d) in map) {
            val j = JSONObject().put("c", d.choice.name)
            d.applied?.let { j.put("a", JSONObject().put("b", it.before).put("n", it.now).put("t", it.atMs)) }
            o.put(k, j)
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

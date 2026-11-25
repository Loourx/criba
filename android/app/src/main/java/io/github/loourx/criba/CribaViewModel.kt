package io.github.loourx.criba

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.loourx.criba.data.AlbumInfo
import io.github.loourx.criba.data.Choice
import io.github.loourx.criba.data.Decisions
import io.github.loourx.criba.data.Item
import io.github.loourx.criba.data.MediaRepo
import io.github.loourx.criba.data.Mode
import io.github.loourx.criba.data.Mover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { START, REVIEW, RECAP }

/** De dónde sale la lista del repaso. */
enum class RecapSource { BATCH, PENDING, MOVED }

/** Fin de un álbum dentro de la tanda: se enseña un resumen antes de seguir. */
data class Interlude(val album: String, val next: String?)

data class ApplyProgress(val done: Int, val total: Int, val errors: List<String>, val running: Boolean)

data class Stats(
    val albums: List<AlbumInfo> = emptyList(),
    val counts: Map<Choice, Pair<Int, Long>> = emptyMap(),
    val moved: Pair<Int, Long> = 0 to 0L,
)

private data class Step(val ids: List<String>, val previous: List<Choice?>, val index: Int, val skip: Boolean = false)

class CribaViewModel(app: Application) : AndroidViewModel(app) {

    private val decisions = Decisions(app)
    private val mover = Mover(app, decisions)

    var loading by mutableStateOf(true); private set
    var items by mutableStateOf<List<Item>>(emptyList()); private set
    var stats by mutableStateOf(Stats()); private set
    var version by mutableIntStateOf(0); private set // cambia cada vez que cambia una decisión

    var screen by mutableStateOf(Screen.START); private set
    var mode by mutableStateOf(Mode.HEAVY_VIDEOS)
    var album by mutableStateOf<String?>(null)
    var batchSize by mutableIntStateOf(50)

    var batch by mutableStateOf<List<Item>>(emptyList()); private set
    var index by mutableIntStateOf(0); private set
    var interlude by mutableStateOf<Interlude?>(null); private set
    var recapItems by mutableStateOf<List<Item>>(emptyList()); private set
    var recapSource by mutableStateOf(RecapSource.BATCH); private set
    var recapAlbum by mutableStateOf<String?>(null); private set
    var recapTab by mutableStateOf(Choice.DELETE)
    var progress by mutableStateOf<ApplyProgress?>(null); private set
    var message by mutableStateOf<String?>(null)
    var toEmpty by mutableStateOf(0 to 0L); private set

    /** Lotes pendientes de la petición de borrado a Android. */
    var deleteQueue by mutableStateOf<List<List<Uri>>>(emptyList()); private set
    private var deletedSomething = false

    private val history = ArrayList<Step>()
    private val skipped = HashSet<String>()          // "saltar álbum" solo dura la sesión
    private val restoring = HashSet<String>()
    private val lastNonKeep = HashMap<String, Choice>() // para que Apartar no acabe en Borrar sin querer
    private var dirty = false

    val running: Boolean get() = progress?.running == true

    init {
        reload()
    }

    private var loadJob: Job? = null

    /** Lee todo de nuevo. Si ya se está leyendo, no lanza una segunda lectura a la vez. */
    fun reload() {
        if (loadJob?.isActive == true) return
        loading = true
        loadJob = viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    decisions.load()
                    mover.reconcile()
                    forgetVanished()
                    MediaRepo.load(getApplication())
                }
            }
            loaded.onSuccess { items = it }.onFailure { message = "No se pudo leer el almacenamiento: ${it.message}" }
            decisions.writeError?.let { message = it }
            dirty = false
            loading = false
            refreshStats()
            refreshEmptyCount()
        }
    }

    /** Decisiones aplicadas cuyo archivo ya no existe (lo borraste tú): se olvidan. */
    private fun forgetVanished() {
        val roots = mover.roots()
        for ((id, d) in decisions.entries().toList()) {
            val a = d.applied ?: continue
            val mounted = roots.any { a.now.startsWith("$it/") }
            if (mounted && !File(a.now).exists()) decisions.forget(id)
        }
    }

    fun refreshStats() {
        val snapshot = items
        viewModelScope.launch {
            stats = withContext(Dispatchers.Default) {
                val albums = snapshot.groupBy { it.album }.map { (name, list) ->
                    AlbumInfo(name, list.size, list.sumOf { it.size }, list.count { it.isVideo },
                        list.count { !decisions.contains(it.id) })
                }.sortedByDescending { it.bytes }
                val bySize = HashMap<String, Long>(snapshot.size * 2)
                snapshot.forEach { bySize[it.id] = it.size }
                val counts = HashMap<Choice, Pair<Int, Long>>()
                var moved = 0
                var movedBytes = 0L
                for ((id, d) in decisions.entries()) {
                    val a = d.applied
                    if (a != null) { moved++; movedBytes += a.size; continue }
                    val itemSize = bySize[id] ?: continue // solo lo que existe ahora
                    val (n, b) = counts[d.choice] ?: (0 to 0L)
                    counts[d.choice] = (n + 1) to (b + itemSize)
                }
                Stats(albums, counts, moved to movedBytes)
            }
        }
    }

    fun refreshEmptyCount() {
        viewModelScope.launch {
            toEmpty = withContext(Dispatchers.IO) {
                val files = mover.filesToEmpty()
                files.size to files.sumOf { it.length() }
            }
        }
    }

    fun choiceOf(id: String): Choice? =
        if (id in skipped) Choice.SKIP else decisions.get(id)?.choice

    fun isApplied(id: String): Boolean = decisions.get(id)?.applied != null

    // ---------- tanda ----------

    fun startBatch() {
        if (running) return
        val snapshot = items
        val m = mode
        val a = album
        val size = batchSize
        viewModelScope.launch {
            val chosen = withContext(Dispatchers.Default) {
                var pool = snapshot.filter { !decisions.contains(it.id) && it.id !in skipped }
                pool = when (m) {
                    Mode.HEAVY_VIDEOS -> pool.filter { it.isVideo && !it.whatsapp }.sortedByDescending { it.size }
                    Mode.BIGGEST -> pool.sortedByDescending { it.size }
                    Mode.ALBUM -> pool.filter { it.album == a }.sortedBy { it.dateMs }
                    Mode.BY_DATE -> pool.sortedBy { it.dateMs }
                    Mode.WHATSAPP -> pool.filter { it.whatsapp }.sortedByDescending { it.size }
                }.take(size)
                // Agrupar por álbum, en el orden en que aparece cada uno.
                val order = LinkedHashMap<String, Int>()
                pool.forEach { order.getOrPut(it.album) { order.size } }
                pool.sortedBy { order[it.album] }
            }
            if (chosen.isEmpty()) {
                message = "No queda nada sin revisar aquí."
                return@launch
            }
            batch = chosen
            index = 0
            history.clear()
            interlude = null
            progress = null
            screen = Screen.REVIEW
        }
    }

    val current: Item? get() = batch.getOrNull(index)

    fun decide(choice: Choice) {
        if (interlude != null || running) return
        val item = current ?: return
        if (!isApplied(item.id)) {
            history += Step(listOf(item.id), listOf(decisions.get(item.id)?.choice), index)
            decisions.set(listOf(item.id), choice)
            version++
        }
        index++
        advance(item.album)
    }

    fun skipAlbum() {
        if (interlude != null || running) return
        val item = current ?: return
        val ids = batch.drop(index).filter { it.album == item.album && choiceOf(it.id) == null }.map { it.id }
        history += Step(ids, ids.map { null }, index, skip = true)
        skipped += ids
        version++
        advance(item.album)
    }

    private fun advance(fromAlbum: String) {
        while (index < batch.size && batch[index].id in skipped) index++
        val next = batch.getOrNull(index)
        if (next == null || next.album != fromAlbum) interlude = Interlude(fromAlbum, next?.album)
    }

    fun undo() {
        if (running) return
        val step = history.lastOrNull() ?: return
        if (step.ids.any { isApplied(it) }) {
            message = "Eso ya se movió. Devuélvelo desde el repaso."
            return
        }
        history.removeAt(history.lastIndex)
        if (step.skip) {
            skipped -= step.ids.toSet()
        } else {
            step.ids.zip(step.previous).groupBy({ it.second }, { it.first }).forEach { (prev, ids) ->
                decisions.set(ids, prev)
            }
        }
        version++
        index = step.index
        interlude = null
        screen = Screen.REVIEW
    }

    fun continueAfterInterlude() {
        val i = interlude ?: return
        interlude = null
        if (i.next == null) openRecap(null)
    }

    fun backToStart() {
        if (running) return
        screen = Screen.START
        interlude = null
        if (dirty) reload() else refreshStats()
        refreshEmptyCount()
    }

    // ---------- repaso ----------

    fun openRecap(albumName: String?) {
        recapSource = RecapSource.BATCH
        recapAlbum = albumName
        recapItems = if (albumName == null) batch else batch.filter { it.album == albumName }
        showRecap()
    }

    /** Lo decidido y aún sin mover, de cualquier tanda (también de sesiones anteriores). */
    fun openPending() {
        if (running) return
        recapSource = RecapSource.PENDING
        recapAlbum = null
        recapItems = items.filter {
            val d = decisions.get(it.id)
            d != null && d.applied == null && (d.choice == Choice.DELETE || d.choice == Choice.SET_ASIDE)
        }
        showRecap()
    }

    /** Lo ya movido a las carpetas de revisión, para poder devolverlo. */
    fun openMoved() {
        if (running) return
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                decisions.entries().mapNotNull { (id, d) -> d.applied?.let { MediaRepo.itemFromFile(id, it.now) } }
                    .sortedByDescending { it.size }
            }
            recapSource = RecapSource.MOVED
            recapAlbum = null
            recapItems = list
            showRecap()
        }
    }

    private fun showRecap() {
        val list = recapItems
        recapTab = when {
            list.any { choiceOf(it.id) == Choice.DELETE } -> Choice.DELETE
            list.any { choiceOf(it.id) == Choice.SET_ASIDE } -> Choice.SET_ASIDE
            else -> Choice.KEEP
        }
        if (!running) progress = null
        screen = Screen.RECAP
    }

    fun leaveRecap() {
        if (running) return
        when {
            recapSource != RecapSource.BATCH -> backToStart()
            recapAlbum != null && interlude?.next != null -> { interlude = null; screen = Screen.REVIEW }
            index < batch.size -> { interlude = null; screen = Screen.REVIEW }
            else -> backToStart()
        }
    }

    fun toggle(item: Item) {
        if (running) return
        if (isApplied(item.id)) {
            restore(item)
            return
        }
        val now = decisions.get(item.id)?.choice
        val to = when (now) {
            Choice.DELETE, Choice.SET_ASIDE -> { lastNonKeep[item.id] = now; Choice.KEEP }
            else -> lastNonKeep[item.id] ?: Choice.DELETE
        }
        decisions.set(listOf(item.id), to)
        version++
    }

    private fun restore(item: Item) {
        if (!restoring.add(item.id)) return
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                mover.restore(item.id).also { res -> res.getOrNull()?.let { mover.scan(it.toList()) } }
            }
            restoring -= item.id
            r.onSuccess { (_, back) ->
                // El elemento vuelve a estar en su sitio (puede que con otro nombre si el original estaba ocupado).
                fun fix(list: List<Item>) = list.map { if (it.id == item.id) it.copy(path = back, mediaUri = null) else it }
                batch = fix(batch)
                recapItems = fix(recapItems)
                dirty = true
            }.onFailure { message = it.message }
            version++
            refreshEmptyCount()
        }
    }

    fun pendingToApply(): List<Item> = recapItems.filter {
        val d = decisions.get(it.id)
        d != null && d.applied == null && (d.choice == Choice.DELETE || d.choice == Choice.SET_ASIDE)
    }

    fun applyMoves() {
        val list = pendingToApply()
        if (list.isEmpty() || running) return
        progress = ApplyProgress(0, list.size, emptyList(), true)
        viewModelScope.launch {
            val errors = ArrayList<String>()
            val changed = ArrayList<String>()
            var done = 0
            for (item in list) {
                // Se vuelve a leer justo antes de mover: si cambió, se respeta lo último.
                val choice = decisions.get(item.id)?.choice
                if (choice == Choice.DELETE || choice == Choice.SET_ASIDE) {
                    val r = withContext(Dispatchers.IO) { mover.apply(item, choice) }
                    r.onSuccess { changed += it.first; changed += it.second }
                        .onFailure { errors += "${item.name}: ${it.message}" }
                }
                done++
                progress = ApplyProgress(done, list.size, errors.toList(), true)
                version++
            }
            withContext(Dispatchers.IO) { mover.scan(changed) }
            progress = ApplyProgress(done, list.size, errors.toList(), false)
            decisions.writeError?.let { message = it }
            dirty = true
            refreshEmptyCount()
        }
    }

    // ---------- vaciar ----------

    fun startEmpty() {
        viewModelScope.launch {
            val uris = withContext(Dispatchers.IO) { mover.urisToEmpty() }
            deletedSomething = false
            if (uris.isEmpty()) finishEmpty() else deleteQueue = uris.chunked(400)
        }
    }

    /** Resultado del diálogo de Android para el lote actual. */
    fun onDeleteResult(ok: Boolean) {
        if (ok) deletedSomething = true
        val rest = deleteQueue.drop(1)
        if (ok && rest.isNotEmpty()) deleteQueue = rest else { deleteQueue = emptyList(); finishEmpty() }
    }

    private fun finishEmpty() {
        viewModelScope.launch {
            val left = withContext(Dispatchers.IO) {
                forgetVanished()
                mover.filesToEmpty()
            }
            toEmpty = left.size to left.sumOf { it.length() }
            message = when {
                left.isEmpty() -> "Carpeta vaciada."
                !deletedSomething -> "No se ha borrado nada."
                else -> "Quedan ${left.size} archivos en ${MediaRepo.DELETE_DIR}. Puedes volver a vaciar o borrarlos desde la galería."
            }
            refreshStats()
        }
    }
}

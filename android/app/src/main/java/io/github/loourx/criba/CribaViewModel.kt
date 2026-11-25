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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { START, REVIEW, RECAP }

/** Fin de un álbum dentro de la tanda: se enseña un resumen antes de seguir. */
data class Interlude(val album: String, val next: String?)

data class ApplyProgress(val done: Int, val total: Int, val errors: List<String>, val running: Boolean)

private data class Step(val ids: List<String>, val previous: List<Choice?>, val index: Int)

class CribaViewModel(app: Application) : AndroidViewModel(app) {

    private val decisions = Decisions(app)
    private val mover = Mover(app, decisions)

    var loading by mutableStateOf(true); private set
    var items by mutableStateOf<List<Item>>(emptyList()); private set
    var version by mutableIntStateOf(0); private set // cambia cada vez que cambia una decisión

    var screen by mutableStateOf(Screen.START); private set
    var mode by mutableStateOf(Mode.HEAVY_VIDEOS)
    var album by mutableStateOf<String?>(null)
    var batchSize by mutableIntStateOf(50)

    var batch by mutableStateOf<List<Item>>(emptyList()); private set
    var index by mutableIntStateOf(0); private set
    var interlude by mutableStateOf<Interlude?>(null); private set
    var recapAlbum by mutableStateOf<String?>(null); private set
    var recapTab by mutableStateOf(Choice.DELETE)
    var progress by mutableStateOf<ApplyProgress?>(null); private set
    var message by mutableStateOf<String?>(null)
    var toEmpty by mutableStateOf(0 to 0L); private set

    private val history = ArrayList<Step>()

    init {
        reload()
    }

    fun reload() {
        loading = true
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                mover.reconcile()
                MediaRepo.load(getApplication())
            }
            items = loaded
            loading = false
            refreshEmptyCount()
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

    fun choiceOf(id: String): Choice? = decisions.get(id)?.choice
    fun isApplied(id: String): Boolean = decisions.get(id)?.applied != null

    fun albums(): List<AlbumInfo> {
        val all = decisions.all()
        return items.groupBy { it.album }.map { (name, list) ->
            AlbumInfo(name, list.size, list.sumOf { it.size }, list.count { it.isVideo }, list.count { it.id !in all })
        }.sortedByDescending { it.bytes }
    }

    fun totals(): Map<Choice, Pair<Int, Long>> {
        val bySize = items.associateBy({ it.id }, { it.size })
        return decisions.all().entries.groupBy { it.value.choice }
            .mapValues { (_, e) -> e.size to e.sumOf { bySize[it.key] ?: 0L } }
    }

    // ---------- tanda ----------

    fun startBatch(): Boolean {
        val all = decisions.all()
        var pool = items.filter { it.id !in all }
        pool = when (mode) {
            Mode.HEAVY_VIDEOS -> pool.filter { it.isVideo && !it.whatsapp }.sortedByDescending { it.size }
            Mode.BIGGEST -> pool.sortedByDescending { it.size }
            Mode.ALBUM -> pool.filter { it.album == album }.sortedBy { it.dateMs }
            Mode.BY_DATE -> pool.sortedBy { it.dateMs }
            Mode.WHATSAPP -> pool.filter { it.whatsapp }.sortedByDescending { it.size }
        }
        pool = pool.take(batchSize)
        // Agrupar por álbum, en el orden en que aparece cada uno.
        val order = LinkedHashMap<String, Int>()
        pool.forEach { order.getOrPut(it.album) { order.size } }
        batch = pool.sortedBy { order[it.album] }
        if (batch.isEmpty()) {
            message = "No queda nada sin revisar aquí."
            return false
        }
        index = 0
        history.clear()
        interlude = null
        progress = null
        screen = Screen.REVIEW
        return true
    }

    val current: Item? get() = batch.getOrNull(index)

    fun decide(choice: Choice) {
        val item = current ?: return
        if (isApplied(item.id)) { index++; advance(item.album); return }
        history += Step(listOf(item.id), listOf(choiceOf(item.id)), index)
        decisions.set(listOf(item.id), choice)
        version++
        index++
        advance(item.album)
    }

    fun skipAlbum() {
        val item = current ?: return
        val ids = batch.drop(index).filter { it.album == item.album && choiceOf(it.id) == null }.map { it.id }
        history += Step(ids, ids.map { null }, index)
        decisions.set(ids, Choice.SKIP)
        version++
        advance(item.album)
    }

    private fun advance(fromAlbum: String) {
        while (index < batch.size && choiceOf(batch[index].id) == Choice.SKIP) index++
        val next = batch.getOrNull(index)
        if (next == null || next.album != fromAlbum) interlude = Interlude(fromAlbum, next?.album)
    }

    fun undo() {
        val step = history.lastOrNull() ?: return
        if (step.ids.any { isApplied(it) }) {
            message = "Eso ya se movió. Devuélvelo desde el repaso."
            return
        }
        history.removeAt(history.lastIndex)
        step.ids.zip(step.previous).groupBy({ it.second }, { it.first }).forEach { (prev, ids) ->
            decisions.set(ids, prev)
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
        screen = Screen.START
        interlude = null
        refreshEmptyCount()
    }

    // ---------- repaso ----------

    fun openRecap(albumName: String?) {
        recapAlbum = albumName
        val list = recapList()
        recapTab = when {
            list.any { choiceOf(it.id) == Choice.DELETE } -> Choice.DELETE
            list.any { choiceOf(it.id) == Choice.SET_ASIDE } -> Choice.SET_ASIDE
            else -> Choice.KEEP
        }
        screen = Screen.RECAP
    }

    fun recapList(): List<Item> = if (recapAlbum == null) batch else batch.filter { it.album == recapAlbum }

    fun leaveRecap() {
        if (recapAlbum != null && interlude?.next != null) {
            interlude = null
            screen = Screen.REVIEW
        } else if (index < batch.size) {
            interlude = null
            screen = Screen.REVIEW
        } else {
            backToStart()
        }
    }

    fun toggle(item: Item) {
        if (isApplied(item.id)) {
            restore(item)
            return
        }
        val now = choiceOf(item.id)
        val to = if (now == Choice.KEEP) Choice.DELETE else Choice.KEEP
        history += Step(listOf(item.id), listOf(now), index)
        decisions.set(listOf(item.id), to)
        version++
    }

    private fun restore(item: Item) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { mover.restore(item.id) }
            r.onFailure { message = it.message }
            version++
            refreshEmptyCount()
        }
    }

    fun pendingToApply(): List<Item> = recapList().filter {
        val c = choiceOf(it.id)
        (c == Choice.DELETE || c == Choice.SET_ASIDE) && !isApplied(it.id)
    }

    fun applyMoves() {
        val list = pendingToApply()
        if (list.isEmpty() || progress?.running == true) return
        progress = ApplyProgress(0, list.size, emptyList(), true)
        viewModelScope.launch {
            val errors = ArrayList<String>()
            var done = 0
            for (item in list) {
                val choice = choiceOf(item.id) ?: continue
                val r = withContext(Dispatchers.IO) { mover.apply(item, choice) }
                r.onFailure { errors += "${item.name}: ${it.message}" }
                done++
                progress = ApplyProgress(done, list.size, errors.toList(), true)
                version++
            }
            progress = ApplyProgress(done, list.size, errors.toList(), false)
            refreshEmptyCount()
        }
    }

    // ---------- vaciar ----------

    suspend fun urisToEmpty(): List<Uri> = withContext(Dispatchers.IO) { mover.urisToEmpty() }

    fun afterEmpty(cancelled: Boolean) {
        viewModelScope.launch {
            val left = withContext(Dispatchers.IO) { mover.filesToEmpty() }
            toEmpty = left.size to left.sumOf { it.length() }
            message = when {
                cancelled -> "No se ha borrado nada."
                left.isEmpty() -> "Carpeta vaciada."
                else -> "Quedan ${left.size} archivos que Android no dejó borrar desde aquí. Bórralos desde la galería o el gestor de archivos."
            }
        }
    }
}

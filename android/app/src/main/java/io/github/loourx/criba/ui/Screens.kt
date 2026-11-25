package io.github.loourx.criba.ui

import android.app.Activity
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.loourx.criba.CribaViewModel
import io.github.loourx.criba.RecapSource
import io.github.loourx.criba.Screen
import io.github.loourx.criba.data.Choice
import io.github.loourx.criba.data.Item
import io.github.loourx.criba.data.MediaRepo
import io.github.loourx.criba.data.Mode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

fun fmtBytes(b: Long): String =
    if (b >= 1_000_000_000) String.format(Locale("es"), "%.2f GB", b / 1e9)
    else "${(b / 1_000_000).coerceAtLeast(if (b > 0) 1 else 0)} MB"

fun fmtDuration(ms: Long): String {
    if (ms <= 0) return ""
    val s = ms / 1000
    return "${s / 60}:${"%02d".format(s % 60)}"
}

private val dateFormat = SimpleDateFormat("d MMM yyyy, HH:mm", Locale("es"))
fun fmtDate(ms: Long): String = if (ms <= 0) "—" else dateFormat.format(Date(ms))

fun colorOf(c: Choice?): Color = when (c) {
    Choice.KEEP -> Keep
    Choice.DELETE -> Del
    Choice.SET_ASIDE -> Aside
    Choice.SKIP -> Warn
    null -> Muted
}

@Composable
fun CribaApp(vm: CribaViewModel) {
    when {
        vm.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("Leyendo fotos y vídeos…", color = Muted)
            }
        }
        vm.screen == Screen.START -> StartScreen(vm)
        vm.screen == Screen.REVIEW -> ReviewScreen(vm)
        vm.screen == Screen.RECAP -> RecapScreen(vm)
    }
    vm.message?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.message = null },
            confirmButton = { TextButton(onClick = { vm.message = null }) { Text("Vale") } },
            text = { Text(msg) },
        )
    }
}

// ---------------------------------------------------------------- inicio

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartScreen(vm: CribaViewModel) {
    val totals = vm.stats.counts
    var confirmEmpty by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Android pide confirmación para cada lote; al responder se lanza el siguiente.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        vm.onDeleteResult(r.resultCode == Activity.RESULT_OK)
    }
    val nextChunk = vm.deleteQueue.firstOrNull()
    LaunchedEffect(nextChunk) {
        if (nextChunk != null) {
            val req = MediaStore.createDeleteRequest(context.contentResolver, nextChunk)
            launcher.launch(IntentSenderRequest.Builder(req.intentSender).build())
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Criba", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Decide qué conservar. Nada se borra del móvil hasta que tú lo confirmes.", color = Muted)
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf(Choice.KEEP, Choice.DELETE, Choice.SET_ASIDE).forEach { c ->
                    val (n, b) = totals[c] ?: (0 to 0L)
                    Text("${c.label}: $n" + if (c != Choice.KEEP) " · ${fmtBytes(b)}" else "",
                        color = colorOf(c), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            }
        }
        item {
            val (n, b) = vm.toEmpty
            Column(
                Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Cómo se borra", fontWeight = FontWeight.Bold)
                Text("Lo que marcas para borrar se mueve a ${MediaRepo.DELETE_DIR}. Lo apartado va a ${MediaRepo.ASIDE_DIR}, para copiarlo luego al PC o a la nube. Las dos carpetas salen como álbumes en la galería.",
                    color = Muted, fontSize = 13.sp)
                val pendingCount = (totals[Choice.DELETE]?.first ?: 0) + (totals[Choice.SET_ASIDE]?.first ?: 0)
                if (pendingCount > 0) {
                    OutlinedButton(onClick = { vm.openPending() }) { Text("Decidido sin mover: $pendingCount") }
                }
                if (vm.stats.moved.first > 0) {
                    OutlinedButton(onClick = { vm.openMoved() }) {
                        Text("Ver lo movido (${vm.stats.moved.first}) y devolver")
                    }
                }
                if (n > 0) {
                    Button(onClick = { confirmEmpty = true }, colors = ButtonDefaults.buttonColors(containerColor = Del)) {
                        Text("Vaciar ${MediaRepo.DELETE_DIR} ($n · ${fmtBytes(b)})")
                    }
                }
            }
        }
        item { Text("Qué revisar", fontWeight = FontWeight.Bold) }
        items(Mode.entries) { m ->
            Selectable(selected = vm.mode == m, onClick = { vm.mode = m }) {
                Text(m.title, fontWeight = FontWeight.Bold)
                Text(m.hint, color = Muted, fontSize = 13.sp)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tanda:")
                listOf(20, 50, 200, 500).forEach { n ->
                    Selectable(selected = vm.batchSize == n, onClick = { vm.batchSize = n }, compact = true) { Text("$n") }
                }
            }
        }
        if (vm.mode == Mode.ALBUM && vm.album != null) {
            item { Text("Álbum: ${vm.album}", color = Accent) }
        }
        item {
            Button(
                onClick = { vm.startBatch() },
                enabled = vm.mode != Mode.ALBUM || vm.album != null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (vm.mode == Mode.ALBUM && vm.album == null) "Elige un álbum abajo" else "Empezar tanda") }
        }
        item { Text("Álbumes", fontWeight = FontWeight.Bold) }
        items(vm.stats.albums, key = { it.name }) { a ->
            Row(
                Modifier.fillMaxWidth()
                    .background(if (vm.album == a.name && vm.mode == Mode.ALBUM) Panel else Color.Transparent, RoundedCornerShape(6.dp))
                    .clickable { vm.album = a.name; vm.mode = Mode.ALBUM }
                    .padding(vertical = 8.dp, horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(a.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(fmtBytes(a.bytes), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Muted)
                Text("${a.pending}/${a.count}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Muted)
            }
        }
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Vaciar ${MediaRepo.DELETE_DIR}") },
            text = {
                val (n, b) = vm.toEmpty
                Text("Hay $n archivos (${fmtBytes(b)}) en ${MediaRepo.DELETE_DIR}. Android te enseñará la lista y te pedirá confirmar. " +
                    "Lo que borres desaparece para siempre: no pasa por la papelera de la galería. Si son muchos, te lo pedirá por partes.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmpty = false
                    vm.startEmpty()
                }) { Text("Continuar", color = Del) }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Selectable(selected: Boolean, onClick: () -> Unit, compact: Boolean = false, content: @Composable () -> Unit) {
    Column(
        Modifier
            .then(if (compact) Modifier else Modifier.fillMaxWidth())
            .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Accent else Line), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 10.dp),
    ) { content() }
}

// ---------------------------------------------------------------- revisión

@Composable
private fun ReviewScreen(vm: CribaViewModel) {
    @Suppress("UNUSED_EXPRESSION") vm.version // repinta al cambiar decisiones
    BackHandler { vm.backToStart() }
    val item = vm.current
    val interlude = vm.interlude
    var muted by remember { mutableStateOf(true) }
    var playerError by remember(item?.id) { mutableStateOf(false) }
    val player = rememberPlayer()
    val context = LocalContext.current
    LaunchedEffect(interlude, item?.isVideo) { if (interlude != null || item?.isVideo != true) player.pause() }
    LaunchedEffect(vm.index) { prefetchPhotos(context, vm.batch.drop(vm.index + 1).take(2)) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        // barra superior
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(interlude?.album ?: item?.album ?: "", fontWeight = FontWeight.Bold, fontSize = 17.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${(vm.index + 1).coerceAtMost(vm.batch.size)} / ${vm.batch.size}", color = Muted,
                fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
        LinearProgressIndicator(progress = { vm.index.toFloat() / vm.batch.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (interlude != null) {
                InterludeView(vm)
            } else if (item != null) {
                SwipeArea(
                    key = item.id,
                    onLeft = { vm.decide(Choice.DELETE) },
                    onRight = { vm.decide(Choice.KEEP) },
                    onUp = { vm.decide(Choice.SET_ASIDE) },
                    onTap = { if (item.isVideo) { if (player.isPlaying) player.pause() else player.play() } },
                ) {
                    if (item.isVideo && playerError) {
                        Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Este vídeo no se puede reproducir aquí.", color = Muted)
                            Text("Puedes decidir por el nombre y el tamaño.", color = Muted, fontSize = 13.sp)
                        }
                    } else if (item.isVideo) {
                        Video(item, player, muted, onError = { playerError = true }, modifier = Modifier.fillMaxSize())
                    } else {
                        Photo(item, Modifier.fillMaxSize())
                    }
                }
            }
        }

        if (interlude == null && item != null) {
            ItemInfo(vm, item)
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton("Borrar", Del, Modifier.weight(1f)) { vm.decide(Choice.DELETE) }
                BigButton("Apartar", Aside, Modifier.weight(1f)) { vm.decide(Choice.SET_ASIDE) }
                BigButton("Conservar", Keep, Modifier.weight(1f)) { vm.decide(Choice.KEEP) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Deshacer", Modifier.weight(1f)) { vm.undo() }
                SmallButton("Saltar álbum", Modifier.weight(1f)) { vm.skipAlbum() }
                if (item.isVideo) SmallButton(if (muted) "Sonido" else "Silencio", Modifier.weight(1f)) { muted = !muted }
                SmallButton("Repaso", Modifier.weight(1f)) { vm.openRecap(null) }
            }
            Text("Desliza: ← borrar · → conservar · ↑ apartar", color = Muted, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp))
        }
    }
}

/** Zona de la foto o el vídeo con gestos. Ignora los que empiezan en los bordes (el "atrás" del sistema). */
@Composable
private fun SwipeArea(
    key: String,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onUp: () -> Unit,
    onTap: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val edge = with(density) { 36.dp.toPx() }
    val threshold = with(density) { 80.dp.toPx() }
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .pointerInput(key) {
                var start = Offset.Zero
                var total = Offset.Zero
                var valid = false
                detectDragGestures(
                    onDragStart = { o ->
                        start = o; total = Offset.Zero
                        valid = o.x > edge && o.x < size.width - edge && o.y < size.height - edge
                    },
                    onDrag = { change, drag -> change.consume(); total += drag },
                    onDragEnd = {
                        if (!valid) return@detectDragGestures
                        val dx = total.x
                        val dy = total.y
                        when {
                            abs(dx) > abs(dy) && abs(dx) > threshold -> if (dx < 0) onLeft() else onRight()
                            -dy > threshold && abs(dy) > abs(dx) -> onUp()
                        }
                    },
                )
            }
            .pointerInput(key) { detectTapGestures(onTap = { onTap() }) },
    ) { content() }
}

@Composable
private fun ItemInfo(vm: CribaViewModel, item: Item) {
    val folder = item.path.removePrefix(MediaRepo.PRIMARY_ROOT + "/").substringBeforeLast('/')
    val left = vm.batch.drop(vm.index).count { it.album == item.album } - 1
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
        Text(item.name, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(folder, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(fmtDate(item.dateMs), color = Muted, fontSize = 12.sp)
            Text(fmtBytes(item.size) + if (item.isVideo && item.durationMs > 0) " · " + fmtDuration(item.durationMs) else "",
                color = Muted, fontSize = 12.sp)
            Text(if (left > 0) "quedan $left del álbum" else "último del álbum", color = Muted, fontSize = 12.sp)
        }
        vm.choiceOf(item.id)?.let { Text("Ahora: ${it.label}", color = colorOf(it), fontSize = 12.sp) }
    }
}

@Composable
private fun BigButton(text: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.height(54.dp), border = BorderStroke(1.5.dp, color),
        shape = RoundedCornerShape(10.dp)) { Text(text, color = color, fontWeight = FontWeight.Bold) }
}

@Composable
private fun SmallButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.height(40.dp), border = BorderStroke(1.dp, Line),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) {
        Text(text, fontSize = 12.sp, maxLines = 1, color = Fg)
    }
}

@Composable
private fun InterludeView(vm: CribaViewModel) {
    val i = vm.interlude ?: return
    val list = vm.batch.filter { it.album == i.album }
    fun count(c: Choice) = list.filter { vm.choiceOf(it.id) == c }.let { it.size to it.sumOf { x -> x.size } }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Fin de “${i.album}”", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        listOf(Choice.DELETE, Choice.SET_ASIDE, Choice.KEEP, Choice.SKIP).forEach { c ->
            val (n, b) = count(c)
            if (n > 0) Text("${c.label}: $n" + if (c != Choice.KEEP && c != Choice.SKIP) " · ${fmtBytes(b)}" else "",
                color = colorOf(c), fontFamily = FontFamily.Monospace)
        }
        Button(onClick = { vm.continueAfterInterlude() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (i.next != null) "Seguir con “${i.next}”" else "Repaso de la tanda", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        OutlinedButton(onClick = { vm.openRecap(i.album) }, modifier = Modifier.fillMaxWidth()) { Text("Repaso de este álbum") }
        TextButton(onClick = { vm.undo() }) { Text("Deshacer la última decisión", color = Muted) }
    }
}

// ---------------------------------------------------------------- repaso

@Composable
private fun RecapScreen(vm: CribaViewModel) {
    @Suppress("UNUSED_EXPRESSION") vm.version
    BackHandler { if (!vm.running) vm.leaveRecap() } // mientras se mueve, "atrás" no hace nada
    val list = vm.recapItems
    val progress = vm.progress
    var armed by remember { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(progress?.running) {
        view.keepScreenOn = progress?.running == true // que no se apague a mitad de mover
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            when (vm.recapSource) {
                RecapSource.PENDING -> "Decidido sin mover"
                RecapSource.MOVED -> "Ya movido"
                RecapSource.BATCH -> "Repaso" + (vm.recapAlbum?.let { " de “$it”" } ?: " de la tanda")
            },
            fontSize = 20.sp, fontWeight = FontWeight.Bold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Choice.DELETE, Choice.SET_ASIDE, Choice.KEEP).forEach { c ->
                val n = list.count { vm.choiceOf(it.id) == c }
                Selectable(selected = vm.recapTab == c, onClick = { vm.recapTab = c }, compact = true) {
                    Text("${c.label} · $n", color = colorOf(c), fontSize = 13.sp)
                }
            }
        }
        Text(
            if (vm.recapTab == Choice.KEEP) "Toca para pasar a Borrar." else "Toca para pasar a Conservar. Si ya está movido, toca para devolverlo a su sitio.",
            color = Muted, fontSize = 12.sp,
        )
        val shown = list.filter { vm.choiceOf(it.id) == vm.recapTab }
        LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown, key = { it.id }) { it ->
                val applied = vm.isApplied(it.id)
                Box(
                    Modifier.aspectRatio(1f)
                        .border(BorderStroke(2.dp, colorOf(vm.choiceOf(it.id))), RoundedCornerShape(6.dp))
                        .clickable(enabled = progress?.running != true) { vm.toggle(it) },
                ) {
                    Thumb(it, Modifier.fillMaxSize())
                    Text(
                        (if (it.isVideo) "▶ " + fmtDuration(it.durationMs) + " " else "") + fmtBytes(it.size),
                        fontSize = 10.sp, color = Color.White, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(horizontal = 4.dp),
                    )
                    if (applied) Text("Movido", fontSize = 10.sp, color = Fg,
                        modifier = Modifier.align(Alignment.TopStart).background(Bg).padding(horizontal = 4.dp))
                }
            }
        }

        val toMove = vm.pendingToApply()
        Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                progress?.running == true -> {
                    Text("Moviendo… ${progress.done} de ${progress.total}", fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                    Text("No cierres la app hasta que termine.", color = Muted, fontSize = 12.sp)
                }
                toMove.isNotEmpty() -> {
                    val del = toMove.count { vm.choiceOf(it.id) == Choice.DELETE }
                    val aside = toMove.size - del
                    Text(if (armed) "Se moverán $del a ${MediaRepo.DELETE_DIR} y $aside a ${MediaRepo.ASIDE_DIR}. No se borra nada."
                         else "$del para borrar y $aside para apartar · ${fmtBytes(toMove.sumOf { it.size })}", fontSize = 13.sp)
                    if (toMove.any { it.whatsapp && it.path.contains("/Private/") })
                        Text("Los privados de WhatsApp se verán en la galería mientras estén en esas carpetas.", color = Warn, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (armed) { armed = false; vm.applyMoves() } else armed = true },
                            colors = ButtonDefaults.buttonColors(containerColor = if (armed) Del else Accent)) {
                            Text(if (armed) "Confirmar: mover ${toMove.size}" else "Mover ${toMove.size}")
                        }
                        if (armed) TextButton(onClick = { armed = false }) { Text("Cancelar") }
                    }
                }
                else -> {
                    val p = progress
                    Text(if (p != null) "Movidos ${p.done - p.errors.size} de ${p.total}." else "Nada pendiente de mover aquí.", fontSize = 13.sp)
                }
            }
            progress?.errors?.takeIf { it.isNotEmpty() }?.let { errs ->
                Text("${errs.size} con error:", color = Del, fontSize = 12.sp)
                errs.take(3).forEach { Text(it, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.leaveRecap() }, enabled = progress?.running != true, modifier = Modifier.weight(1f)) {
                Text(when {
                    vm.recapSource != RecapSource.BATCH -> "Volver al inicio"
                    vm.recapAlbum != null && vm.interlude?.next != null -> "Seguir con “${vm.interlude?.next}”"
                    vm.index < vm.batch.size -> "Volver a la revisión"
                    else -> "Terminar"
                }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

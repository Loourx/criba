package io.github.loourx.criba.ui

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import io.github.loourx.criba.data.Item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** Carga de imágenes compartida. */
object Images {
    @Volatile private var loader: ImageLoader? = null

    fun loader(context: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: ImageLoader.Builder(context.applicationContext)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(false)
            .build().also {
                loader = it
                // Si Android pide memoria, se sueltan las miniaturas.
                context.applicationContext.registerComponentCallbacks(object : ComponentCallbacks2 {
                    override fun onTrimMemory(level: Int) {
                        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) systemThumbs.evictAll()
                    }
                    override fun onConfigurationChanged(newConfig: Configuration) = Unit
                    @Deprecated("Deprecated in Java")
                    override fun onLowMemory() = systemThumbs.evictAll()
                })
            }
    }

    /** Miniaturas que da el sistema (las tiene en caché para la galería). Máximo ~48 MB. */
    val systemThumbs = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val failed = HashSet<String>()
    fun failed(id: String) = synchronized(failed) { id in failed }
    fun markFailed(id: String) = synchronized(failed) { failed += id }

    @kotlin.OptIn(ExperimentalCoroutinesApi::class)
    val systemThumbDispatcher = Dispatchers.IO.limitedParallelism(3)

    /** Los fotogramas de vídeo son más caros: van por separado para no frenar al resto. */
    @kotlin.OptIn(ExperimentalCoroutinesApi::class)
    val videoFrameDispatcher = Dispatchers.IO.limitedParallelism(2)
}

/** Miniatura cuadrada para el repaso. */
@Composable
fun Thumb(item: Item, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (item.mediaUri != null) {
        var bmp by remember(item.id) { mutableStateOf(Images.systemThumbs.get(item.id)) }
        LaunchedEffect(item.id) {
            if (bmp != null || Images.failed(item.id)) return@LaunchedEffect
            bmp = withContext(Images.systemThumbDispatcher) { loadSystemThumb(context, item) }
        }
        Box(modifier.background(Panel)) {
            bmp?.let { Image(it.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
    } else {
        val request = remember(item.id, item.path) {
            ImageRequest.Builder(context).data(File(item.path)).size(320).videoFrameMillis(1000)
                .dispatcher(if (item.isVideo) Images.videoFrameDispatcher else Images.systemThumbDispatcher)
                .memoryCacheKey("t:" + item.path).build()
        }
        AsyncImage(
            model = request, contentDescription = item.name, imageLoader = Images.loader(context),
            contentScale = ContentScale.Crop, modifier = modifier.background(Panel),
        )
    }
}

/** Pide la miniatura al sistema; se cancela si la celda deja de verse. */
private suspend fun loadSystemThumb(context: Context, item: Item): Bitmap? =
    suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        val bmp = try {
            context.contentResolver.loadThumbnail(Uri.parse(item.mediaUri), Size(320, 320), signal)
                .also { Images.systemThumbs.put(item.id, it) }
        } catch (e: Exception) {
            if (!signal.isCanceled) Images.markFailed(item.id)
            null
        }
        if (cont.isActive) cont.resume(bmp)
    }

private fun photoRequest(context: Context, item: Item) =
    ImageRequest.Builder(context).data(File(item.path)).memoryCacheKey("f:" + item.path).build()

/** Foto a pantalla completa. Coil la reduce al tamaño de la pantalla (no carga los 48 MP). */
@Composable
fun Photo(item: Item, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AsyncImage(
        model = photoRequest(context, item),
        contentDescription = item.name,
        imageLoader = Images.loader(context),
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

/** Prepara en segundo plano las siguientes fotos para que aparezcan al instante. */
fun prefetchPhotos(context: Context, next: List<Item>) {
    val loader = Images.loader(context)
    next.filter { !it.isVideo }.forEach { loader.enqueue(photoRequest(context, it)) }
}

/** Un único reproductor para toda la revisión: se reutiliza al pasar de vídeo y se libera al salir. */
@OptIn(UnstableApi::class)
@Composable
fun rememberPlayer(): ExoPlayer {
    val context = LocalContext.current
    val player = remember {
        // Búfer pequeño: es para ver unos segundos, no para cargar vídeos de 400 MB en memoria.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_000, 5_000, 500, 1_000)
            .setTargetBufferBytes(16 * 1024 * 1024)
            .build()
        ExoPlayer.Builder(context).setLoadControl(loadControl).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            // Sin pedir el foco de audio: no corta la música que estés escuchando.
            setAudioAttributes(AudioAttributes.DEFAULT, false)
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_START -> if (player.mediaItemCount > 0) player.play()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            player.release()
        }
    }
    return player
}

@OptIn(UnstableApi::class)
@Composable
fun Video(item: Item, player: ExoPlayer, muted: Boolean, onError: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(item.id) {
        player.setMediaItem(MediaItem.Builder().setMediaId(item.id).setUri(Uri.fromFile(File(item.path))).build())
        player.prepare()
        player.playWhenReady = true
    }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
    DisposableEffect(player, item.id) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Solo cuenta si el error es de este vídeo, no de uno anterior.
                if (player.currentMediaItem?.mediaId == item.id) onError()
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                this.player = player
            }
        },
        update = { it.player = player },
        modifier = modifier,
    )
}

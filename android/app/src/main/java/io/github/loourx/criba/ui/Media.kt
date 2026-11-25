package io.github.loourx.criba.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.withContext
import java.io.File

/** Cargador de imágenes compartido, con soporte para fotogramas de vídeo. */
object Images {
    @Volatile private var loader: ImageLoader? = null

    fun loader(context: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: ImageLoader.Builder(context.applicationContext)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(false)
            .build().also { loader = it }
    }

    /** Miniaturas que da el sistema (ya las tiene en caché para la galería). Máximo ~48 MB. */
    val systemThumbs = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    @kotlin.OptIn(ExperimentalCoroutinesApi::class)
    val thumbDispatcher = Dispatchers.IO.limitedParallelism(3)
}

/** Miniatura cuadrada para el repaso. */
@Composable
fun Thumb(item: Item, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (item.mediaUri != null) {
        val bmp by produceState(Images.systemThumbs.get(item.id), item.id) {
            value = value ?: withContext(Images.thumbDispatcher) {
                runCatching {
                    context.contentResolver.loadThumbnail(Uri.parse(item.mediaUri), Size(320, 320), null)
                }.getOrNull()?.also { Images.systemThumbs.put(item.id, it) }
            }
        }
        Box(modifier.background(Panel)) {
            bmp?.let {
                Image(it.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
    } else {
        val request = remember(item.id) {
            ImageRequest.Builder(context).data(File(item.path)).size(320).videoFrameMillis(1000)
                .dispatcher(Images.thumbDispatcher).memoryCacheKey("t:" + item.id).build()
        }
        AsyncImage(
            model = request, contentDescription = item.name, imageLoader = Images.loader(context),
            contentScale = ContentScale.Crop, modifier = modifier.background(Panel),
        )
    }
}

/** Foto a pantalla completa. Coil la reduce al tamaño de la pantalla (no carga los 48 MP). */
@Composable
fun Photo(item: Item, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context).data(File(item.path)).memoryCacheKey("f:" + item.id).build(),
        contentDescription = item.name,
        imageLoader = Images.loader(context),
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

/** Un único reproductor para toda la revisión: se reutiliza al pasar de vídeo y se libera al salir. */
@OptIn(UnstableApi::class)
@Composable
fun rememberPlayer(): ExoPlayer {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            // Sin pedir el foco de audio: no corta la música que estés escuchando.
            setAudioAttributes(AudioAttributes.DEFAULT, false)
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) player.pause() }
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
fun Video(item: Item, player: ExoPlayer, muted: Boolean, onError: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(item.id) {
        onError(false)
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(item.path))))
        player.prepare()
        player.playWhenReady = true
    }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = onError(true)
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                this.player = player
            }
        },
        update = { it.player = player },
        modifier = modifier,
    )
}

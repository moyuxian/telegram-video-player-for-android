package com.example.telegramcacheplayer

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.io.File

/**
 * Full-screen ExoPlayer. Receives either a single file path or a list of paths
 * + initial index. Persists and restores per-file position via PlaybackProgressStore.
 */
class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Keep the screen on for the entire duration of this activity.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val paths: List<String> = intent.getStringArrayExtra(EXTRA_PATHS)?.toList()
            ?: intent.getStringExtra(EXTRA_PATH)?.let { listOf(it) }
            ?: emptyList()

        if (paths.isEmpty()) {
            finish()
            return
        }

        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)
            .coerceIn(0, paths.lastIndex)

        setContent {
            MaterialTheme {
                PlayerScreen(paths, startIndex)
            }
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_PATHS = "paths"
        const val EXTRA_START_INDEX = "start_index"
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerScreen(paths: List<String>, startIndex: Int) {
    val context = LocalContext.current
    val progressStore = remember { PlaybackProgressStore(context) }
    val lifecycleOwner = LocalLifecycleOwner.current

    var currentTitle by remember { mutableStateOf(File(paths[startIndex]).name) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            val items = paths.map { MediaItem.fromUri(Uri.fromFile(File(it))) }
            setMediaItems(items, startIndex, 0L)
            val savedPos = progressStore.getPosition(paths[startIndex], 0L)
            prepare()
            if (savedPos > 0) seekTo(startIndex, savedPos)
            playWhenReady = true
        }
    }

    // Re-apply saved position on every media transition, update the displayed title.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val idx = player.currentMediaItemIndex
                if (idx in paths.indices) {
                    currentTitle = File(paths[idx]).name
                    val saved = progressStore.getPosition(paths[idx], player.duration)
                    if (saved > 0) player.seekTo(saved)
                }
            }
        }
        player.addListener(listener)
        onDispose {
            val idx = player.currentMediaItemIndex
            if (idx in paths.indices) {
                progressStore.savePosition(
                    paths[idx],
                    player.currentPosition,
                    player.duration,
                )
            }
            player.removeListener(listener)
            player.release()
        }
    }

    // Pause playback when the activity goes to background; resume when it comes back.
    DisposableEffect(lifecycleOwner, player) {
        var wasPlayingBeforePause = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    wasPlayingBeforePause = player.isPlaying
                    player.pause()
                    val idx = player.currentMediaItemIndex
                    if (idx in paths.indices) {
                        progressStore.savePosition(
                            paths[idx],
                            player.currentPosition,
                            player.duration,
                        )
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (wasPlayingBeforePause) player.play()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Periodic progress flush so a kill/crash doesn't lose position.
    LaunchedEffect(player) {
        while (true) {
            delay(5_000L)
            val idx = player.currentMediaItemIndex
            if (idx in paths.indices && player.isPlaying) {
                progressStore.savePosition(
                    paths[idx],
                    player.currentPosition,
                    player.duration,
                )
            }
        }
    }

    var playerViewRef by remember {
        mutableStateOf<PlayerView?>(null)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    // We drive controller visibility manually via the overlay's
                    // single-tap handler, so disable PlayerView's own tap toggle.
                    controllerAutoShow = false
                    controllerHideOnTouch = false
                    setShowNextButton(paths.size > 1)
                    setShowPreviousButton(paths.size > 1)
                    playerViewRef = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Transparent overlay that handles single-tap (toggle controls) and
        // double-tap (±10s seek) without fighting PlayerView for events.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(player) {
                    detectTapGestures(
                        onTap = {
                            val v = playerViewRef ?: return@detectTapGestures
                            if (v.isControllerFullyVisible) v.hideController()
                            else v.showController()
                        },
                        onDoubleTap = { offset ->
                            val half = size.width / 2f
                            val delta = if (offset.x < half) -10_000L else 10_000L
                            val target = (player.currentPosition + delta)
                                .coerceIn(0L, player.duration.coerceAtLeast(0L))
                            player.seekTo(target)
                        },
                    )
                }
        )
        // Top title strip.
        Text(
            text = currentTitle,
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(Color(0x88000000))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

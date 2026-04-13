package com.example.telegramcacheplayer

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.io.File
import java.text.DecimalFormat
import kotlin.math.abs

/**
 * Full-screen ExoPlayer. Receives either a single file path or a list of paths
 * + initial index. Persists and restores per-file position via PlaybackProgressStore.
 */
class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        // Keep the screen on for the entire duration of this activity.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshImmersiveMode()

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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshImmersiveMode()
    }

    fun toggleOrientation(landscape: Boolean) {
        requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        refreshImmersiveMode()
    }

    fun refreshImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_PATHS = "paths"
        const val EXTRA_START_INDEX = "start_index"
    }
}

@UnstableApi
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PlayerScreen(paths: List<String>, startIndex: Int) {
    val context = LocalContext.current
    val activity = context as? PlayerActivity
    val progressStore = remember { PlaybackProgressStore(context) }
    val settingsStore = remember { PlaybackSettingsStore(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val touchSlop = remember {
        ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    }
    val holdSpeedOptions = remember {
        listOf(1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
    }

    var currentTitle by remember { mutableStateOf(File(paths[startIndex]).name) }
    var centerOverlayText by remember { mutableStateOf<String?>(null) }
    var chromeVisible by remember { mutableStateOf(true) }
    var holdBoostSpeed by remember {
        mutableFloatStateOf(settingsStore.getHoldBoostSpeed())
    }
    var speedMenuOpen by remember { mutableStateOf(false) }

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
                    chromeVisible = true
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
                    player.setPlaybackSpeed(1f)
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
                    activity?.refreshImmersiveMode()
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
    var overlayWidthPx by remember { mutableStateOf(0) }
    var downX by remember { mutableFloatStateOf(0f) }
    var downY by remember { mutableFloatStateOf(0f) }
    var dragAnchorPositionMs by remember { mutableLongStateOf(0L) }
    var dragPreviewPositionMs by remember { mutableLongStateOf(0L) }
    var resumeAfterScrub by remember { mutableStateOf(false) }
    var isScrubbing by remember { mutableStateOf(false) }
    var speedBoostActive by remember { mutableStateOf(false) }

    fun showCenter(message: String) {
        centerOverlayText = message
    }

    fun durationForDisplay(): String {
        val duration = player.duration
        return if (duration > 0) formatPlayerDuration(duration) else "--:--"
    }

    fun showPositionHud(positionMs: Long = player.currentPosition) {
        showCenter("${formatPlayerDuration(positionMs)} / ${durationForDisplay()}")
    }

    fun seekToPosition(targetMs: Long, announce: Boolean = true) {
        val duration = player.duration
        val clamped = if (duration > 0) {
            targetMs.coerceIn(0L, duration)
        } else {
            targetMs.coerceAtLeast(0L)
        }
        player.seekTo(clamped)
        if (announce) showPositionHud(clamped)
    }

    fun seekBy(deltaMs: Long, announce: Boolean = true) {
        seekToPosition(player.currentPosition + deltaMs, announce)
    }

    fun scrubSpanMs(durationMs: Long): Long {
        if (durationMs <= 0) return 120_000L
        return (durationMs / 4).coerceIn(90_000L, 15 * 60_000L)
    }

    fun previewTargetFromDrag(deltaX: Float): Long {
        val width = overlayWidthPx.coerceAtLeast(1)
        val span = scrubSpanMs(player.duration)
        val offset = (deltaX / width.toFloat()) * span
        return dragAnchorPositionMs + offset.toLong()
    }

    fun beginScrubIfNeeded() {
        if (isScrubbing) return
        isScrubbing = true
        resumeAfterScrub = player.isPlaying
        player.pause()
    }

    fun finishScrub() {
        if (!isScrubbing) return
        seekToPosition(dragPreviewPositionMs, announce = false)
        showPositionHud(dragPreviewPositionMs)
        isScrubbing = false
        if (resumeAfterScrub) player.play()
    }

    fun startSpeedBoost() {
        if (speedBoostActive || isScrubbing || !player.isPlaying) return
        speedBoostActive = true
        player.setPlaybackSpeed(holdBoostSpeed)
    }

    fun stopSpeedBoost() {
        if (!speedBoostActive) return
        speedBoostActive = false
        player.setPlaybackSpeed(1f)
    }

    val gestureDetector = remember(player, overlayWidthPx) {
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    chromeVisible = !chromeVisible
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (player.isPlaying) {
                        player.pause()
                        showCenter("Paused")
                    } else {
                        player.play()
                        showCenter("Playing")
                    }
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (overlayWidthPx <= 0 || speedBoostActive || isScrubbing) return
                    val edge = overlayWidthPx * 0.32f
                    val isEdge = e.x <= edge || e.x >= overlayWidthPx - edge
                    if (!isEdge) return
                    startSpeedBoost()
                }
            }
        )
    }

    LaunchedEffect(chromeVisible, currentTitle) {
        if (!chromeVisible) return@LaunchedEffect
        delay(2_000L)
        chromeVisible = false
    }

    LaunchedEffect(centerOverlayText) {
        val text = centerOverlayText ?: return@LaunchedEffect
        delay(900L)
        if (centerOverlayText == text) centerOverlayText = null
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
                    useController = false
                    playerViewRef = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { overlayWidthPx = it.width }
                .pointerInteropFilter { event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.x
                            downY = event.y
                            dragAnchorPositionMs = player.currentPosition
                            dragPreviewPositionMs = dragAnchorPositionMs
                            resumeAfterScrub = player.isPlaying
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (!speedBoostActive) {
                                val dx = event.x - downX
                                val dy = event.y - downY
                                val horizontal = abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.2f
                                if (isScrubbing || horizontal) {
                                    beginScrubIfNeeded()
                                    val target = previewTargetFromDrag(dx)
                                    if (abs(target - dragPreviewPositionMs) >= 250L) {
                                        dragPreviewPositionMs = target
                                        seekToPosition(target, announce = false)
                                    }
                                    showPositionHud(dragPreviewPositionMs)
                                    return@pointerInteropFilter true
                                }
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            stopSpeedBoost()
                            finishScrub()
                        }
                    }
                    gestureDetector.onTouchEvent(event)
                    true
                }
        )
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (chromeVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Transparent),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box {
                    OverlayChip(
                        text = "Hold ${formatSpeedLabel(holdBoostSpeed)}",
                        onClick = { speedMenuOpen = true },
                    )
                    DropdownMenu(
                        expanded = speedMenuOpen,
                        onDismissRequest = { speedMenuOpen = false },
                    ) {
                        holdSpeedOptions.forEach { speed ->
                            DropdownMenuItem(
                                text = { Text("Hold ${formatSpeedLabel(speed)}") },
                                onClick = {
                                    holdBoostSpeed = speed
                                    settingsStore.setHoldBoostSpeed(speed)
                                    speedMenuOpen = false
                                    chromeVisible = true
                                    showCenter("Hold ${formatSpeedLabel(speed)}")
                                }
                            )
                        }
                    }
                }
                OverlayChip(
                    text = if (isLandscape) "Portrait" else "Landscape",
                    onClick = {
                        activity?.toggleOrientation(!isLandscape)
                        chromeVisible = true
                        showCenter(if (isLandscape) "Portrait" else "Landscape")
                    },
                )
            }
            Text(
                text = currentTitle,
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .background(Color(0x66000000), RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        val activeCenterText = if (speedBoostActive) {
            "Holding ${formatSpeedLabel(holdBoostSpeed)}"
        } else {
            centerOverlayText
        }
        activeCenterText?.let { text ->
            Text(
                text = text,
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0x88000000), RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun OverlayChip(
    text: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0x66000000))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 12.sp,
        )
    }
}

private fun formatPlayerDuration(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%02d:%02d".format(m, s)
}

private val speedFmt = DecimalFormat("0.##")

private fun formatSpeedLabel(speed: Float): String =
    "${speedFmt.format(speed)}x"

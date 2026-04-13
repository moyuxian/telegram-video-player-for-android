package com.example.telegramcacheplayer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                AppScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen(vm: VideoListViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val sortOrder by vm.sortOrder.collectAsStateWithLifecycle()
    val revision by vm.revision.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val progressStore = remember { PlaybackProgressStore(context) }
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasAllFilesAccess by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                Environment.isExternalStorageManager()
        )
    }
    // Re-check permission + auto-refresh scan every time we return to foreground.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    Environment.isExternalStorageManager()
                val becameGranted = granted && !hasAllFilesAccess
                hasAllFilesAccess = granted
                if (granted) {
                    // Auto-refresh persisted progress bars (user may have just
                    // watched something) and kick off an incremental rescan.
                    vm.bumpRevision()
                    if (becameGranted || vm.state.value !is VideoListState.Scanning) {
                        vm.scan()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var sortMenuOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember {
        mutableStateOf<TelegramFileScanner.Video?>(null)
    }

    fun openAllFilesSettings() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
    }

    fun play(videos: List<TelegramFileScanner.Video>, index: Int) {
        val intent = Intent(context, PlayerActivity::class.java)
            .putExtra(
                PlayerActivity.EXTRA_PATHS,
                videos.map { it.file.path }.toTypedArray()
            )
            .putExtra(PlayerActivity.EXTRA_START_INDEX, index)
        context.startActivity(intent)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    val loaded = state as? VideoListState.Loaded
                    val summary = loaded?.summary
                    if (summary != null) {
                        Column {
                            Text("Telegram Cache Player", fontSize = 16.sp)
                            Text(
                                "${summary.totalCount} videos  ·  ${formatSize(summary.totalBytes)}",
                                fontSize = 11.sp,
                            )
                        }
                    } else {
                        Text("Telegram Cache Player")
                    }
                },
                actions = {
                    val refreshing = (state as? VideoListState.Loaded)?.refreshing == true
                    if (refreshing) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(18.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (!hasAllFilesAccess) {
                Text(
                    "Need All Files Access permission.",
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { openAllFilesSettings() }) {
                    Text("Grant permission")
                }
                return@Column
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.setQuery(it) },
                    placeholder = { Text("Search", fontSize = 13.sp) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp),
                    modifier = Modifier.weight(1f),
                )
                Box {
                    OutlinedButton(onClick = { sortMenuOpen = true }) {
                        Text(sortOrder.label, fontSize = 12.sp)
                    }
                    DropdownMenu(
                        expanded = sortMenuOpen,
                        onDismissRequest = { sortMenuOpen = false },
                    ) {
                        SortOrder.entries.forEach { opt ->
                            DropdownMenuItem(
                                text = { Text(opt.label) },
                                onClick = {
                                    vm.setSortOrder(opt)
                                    sortMenuOpen = false
                                }
                            )
                        }
                    }
                }
                OutlinedButton(onClick = { vm.scan() }) {
                    Text("↻", fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider()

            when (val s = state) {
                VideoListState.Idle -> {
                    Spacer(Modifier.height(16.dp))
                    Text("Idle")
                }
                VideoListState.Scanning -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                is VideoListState.Error -> {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Error: ${s.message}",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                is VideoListState.Loaded -> {
                    if (s.videos.isEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        if (query.isNotBlank()) {
                            Text(
                                "No videos match \"$query\".",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                "No videos found. Scanned roots:",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            TelegramFileScanner.CANDIDATE_ROOTS.forEach { root ->
                                Text(
                                    text = "• $root",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        VideoList(
                            videos = s.videos,
                            groupByDate = sortOrder == SortOrder.MODIFIED_DESC ||
                                sortOrder == SortOrder.MODIFIED_ASC,
                            progressStore = progressStore,
                            revision = revision,
                            onPlay = { idx -> play(s.videos, idx) },
                            onLongPress = { v -> pendingDelete = v },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { video ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete video?") },
            text = {
                Column {
                    Text(video.name, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${formatSize(video.sizeBytes)}  ·  This will remove the file from disk.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(video)
                    pendingDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoList(
    videos: List<TelegramFileScanner.Video>,
    groupByDate: Boolean,
    progressStore: PlaybackProgressStore,
    revision: Int,
    onPlay: (index: Int) -> Unit,
    onLongPress: (TelegramFileScanner.Video) -> Unit,
) {
    // Precompute section labels when grouping by date.
    val sectionLabels = remember(videos, groupByDate) {
        if (!groupByDate) emptyMap<Int, String>()
        else {
            val out = mutableMapOf<Int, String>()
            var last: String? = null
            videos.forEachIndexed { i, v ->
                val label = dateBucket(v.lastModified)
                if (label != last) {
                    out[i] = label
                    last = label
                }
            }
            out
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 6.dp),
    ) {
        videos.forEachIndexed { index, v ->
            val header = sectionLabels[index]
            if (header != null) {
                item(key = "header:$index") {
                    Text(
                        text = header,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                }
            }
            item(key = v.file.path) {
                VideoRow(
                    video = v,
                    progressStore = progressStore,
                    revision = revision,
                    onClick = { onPlay(index) },
                    onLongPress = { onLongPress(v) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoRow(
    video: TelegramFileScanner.Video,
    progressStore: PlaybackProgressStore,
    revision: Int,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val onLongPressWithHaptic = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onLongPress()
    }
    val meta by produceState<VideoMetaCache.Meta?>(
        initialValue = VideoMetaCache.getCached(video.file.path),
        key1 = video.file.path,
    ) {
        if (value == null) value = VideoMetaCache.load(video.file)
    }

    val durationMs = meta?.durationMs ?: 0L
    // Read saved position on every recomposition keyed on revision so playback
    // progress reflects live after the user returns from PlayerActivity.
    val savedPos = remember(video.file.path, durationMs, revision) {
        progressStore.getPosition(video.file.path, durationMs)
    }
    val watchedFraction = if (durationMs > 0 && savedPos > 0)
        (savedPos.toFloat() / durationMs).coerceIn(0f, 1f)
    else 0f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPressWithHaptic,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 120.dp, height = 68.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF222222)),
            contentAlignment = Alignment.BottomEnd,
        ) {
            val bmp = meta?.thumb
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (durationMs > 0) {
                Text(
                    text = formatDuration(durationMs),
                    fontSize = 10.sp,
                    color = Color.White,
                    modifier = Modifier
                        .padding(4.dp)
                        .background(Color(0xAA000000), RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            if (watchedFraction > 0f) {
                LinearProgressIndicator(
                    progress = { watchedFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.BottomCenter),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = video.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${formatSize(video.sizeBytes)}  ·  ${formatDate(video.lastModified)}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (video.relativePath != video.name) {
                val parent = video.relativePath.substringBeforeLast('/', "")
                if (parent.isNotEmpty()) {
                    Text(
                        text = parent,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val sizeFmt = DecimalFormat("#.##")
private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return "${sizeFmt.format(v)} ${units[i]}"
}

private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
private fun formatDate(ts: Long): String =
    if (ts <= 0) "-" else dateFmt.format(Date(ts))

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s)
    else "%d:%02d".format(m, s)
}

/** Bucket a timestamp into a human-friendly section label for the list. */
private fun dateBucket(ts: Long): String {
    if (ts <= 0) return "Unknown"
    val now = Calendar.getInstance()
    val that = Calendar.getInstance().apply { timeInMillis = ts }

    val nowY = now.get(Calendar.YEAR)
    val nowD = now.get(Calendar.DAY_OF_YEAR)
    val thatY = that.get(Calendar.YEAR)
    val thatD = that.get(Calendar.DAY_OF_YEAR)

    if (thatY == nowY && thatD == nowD) return "Today"
    if (thatY == nowY && thatD == nowD - 1) return "Yesterday"
    if (thatY == nowY && thatD > nowD - 7) return "This week"
    if (thatY == nowY && thatD > nowD - 30) return "This month"
    if (thatY == nowY) return "This year"
    return thatY.toString()
}

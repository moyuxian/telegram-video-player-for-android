package com.example.telegramcacheplayer

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SortOrder {
    MODIFIED_DESC, MODIFIED_ASC, NAME_ASC, NAME_DESC, SIZE_DESC, SIZE_ASC;

    val label: String
        get() = when (this) {
            MODIFIED_DESC -> "Newest"
            MODIFIED_ASC -> "Oldest"
            NAME_ASC -> "Name A→Z"
            NAME_DESC -> "Name Z→A"
            SIZE_DESC -> "Largest"
            SIZE_ASC -> "Smallest"
        }
}

enum class LibraryFilter {
    ALL, FAVORITES;

    val label: String
        get() = when (this) {
            ALL -> "All"
            FAVORITES -> "Favorites"
        }
}

sealed interface RawScanState {
    data object Idle : RawScanState
    data object Scanning : RawScanState
    data class Loaded(val videos: List<TelegramFileScanner.Video>) : RawScanState
    data class Error(val message: String) : RawScanState
}

data class ScanSummary(
    val totalCount: Int,
    val totalBytes: Long,
    val newest: Long,
    val oldest: Long,
)

sealed interface VideoListState {
    data object Idle : VideoListState
    data object Scanning : VideoListState
    data class Loaded(
        val videos: List<TelegramFileScanner.Video>,
        val summary: ScanSummary,
        val refreshing: Boolean,
    ) : VideoListState
    data class Error(val message: String) : VideoListState
}

private data class ListControls(
    val query: String,
    val sortOrder: SortOrder,
    val favoritePaths: Set<String>,
    val libraryFilter: LibraryFilter,
)

class VideoListViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("video_list_prefs", android.content.Context.MODE_PRIVATE)
    private val favoritesStore = FavoritesStore(app)

    private val _raw = MutableStateFlow<RawScanState>(RawScanState.Idle)
    private val _refreshing = MutableStateFlow(false)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _sortOrder = MutableStateFlow(loadSortOrder())
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    private val _favoritePaths = MutableStateFlow(favoritesStore.allFavorites())
    val favoritePaths: StateFlow<Set<String>> = _favoritePaths.asStateFlow()

    private val _libraryFilter = MutableStateFlow(LibraryFilter.ALL)
    val libraryFilter: StateFlow<LibraryFilter> = _libraryFilter.asStateFlow()

    private fun loadSortOrder(): SortOrder {
        val name = prefs.getString("sort_order", null) ?: return SortOrder.MODIFIED_DESC
        return runCatching { SortOrder.valueOf(name) }.getOrDefault(SortOrder.MODIFIED_DESC)
    }

    /** Monotonic counter bumped whenever we want to force row savedPos re-read. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val listControls = combine(
        _query, _sortOrder, _favoritePaths, _libraryFilter,
    ) { query, sortOrder, favoritePaths, libraryFilter ->
        ListControls(query, sortOrder, favoritePaths, libraryFilter)
    }

    val state: StateFlow<VideoListState> = combine(
        _raw, _refreshing, listControls,
    ) { raw, refreshing, controls ->
        buildState(raw, refreshing, controls)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, VideoListState.Idle)

    /** Notify rows that persisted progress may have changed (e.g. after playback). */
    fun bumpRevision() { _revision.value += 1 }

    init {
        if (Environment.isExternalStorageManager()) {
            scan()
        }
    }

    fun scan() {
        // Keep showing the old list if we already have one — just flag refreshing.
        val prev = _raw.value
        if (prev !is RawScanState.Loaded) {
            _raw.value = RawScanState.Scanning
        }
        _refreshing.value = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { TelegramFileScanner.scan() }
            }
            _raw.value = result.fold(
                onSuccess = { RawScanState.Loaded(it) },
                onFailure = { RawScanState.Error(it.message ?: it.toString()) },
            )
            _refreshing.value = false
        }
    }

    fun setQuery(q: String) { _query.value = q }
    fun setLibraryFilter(filter: LibraryFilter) { _libraryFilter.value = filter }
    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
        prefs.edit().putString("sort_order", order.name).apply()
    }

    fun toggleFavorite(video: TelegramFileScanner.Video) {
        favoritesStore.toggle(video.file.path)
        _favoritePaths.value = favoritesStore.allFavorites()
    }

    /** Deletes a file from disk and removes it from the in-memory list. */
    fun delete(video: TelegramFileScanner.Video) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                runCatching { video.file.delete() }.getOrDefault(false)
            }
            if (deleted) {
                favoritesStore.remove(video.file.path)
                _favoritePaths.value = favoritesStore.allFavorites()
                val current = _raw.value
                if (current is RawScanState.Loaded) {
                    _raw.value = RawScanState.Loaded(
                        current.videos.filter { it.file.path != video.file.path }
                    )
                }
            }
        }
    }

    private fun buildState(
        raw: RawScanState,
        refreshing: Boolean,
        controls: ListControls,
    ): VideoListState {
        return when (raw) {
            RawScanState.Idle -> VideoListState.Idle
            RawScanState.Scanning -> VideoListState.Scanning
            is RawScanState.Error -> VideoListState.Error(raw.message)
            is RawScanState.Loaded -> {
                val scoped = when (controls.libraryFilter) {
                    LibraryFilter.ALL -> raw.videos
                    LibraryFilter.FAVORITES -> raw.videos.filter {
                        it.file.path in controls.favoritePaths
                    }
                }
                val filtered = if (controls.query.isBlank()) scoped else {
                    val needle = controls.query.trim().lowercase()
                    scoped.filter {
                        it.name.lowercase().contains(needle) ||
                            it.relativePath.lowercase().contains(needle)
                    }
                }
                val sorted = when (controls.sortOrder) {
                    SortOrder.MODIFIED_DESC -> filtered.sortedByDescending { it.lastModified }
                    SortOrder.MODIFIED_ASC -> filtered.sortedBy { it.lastModified }
                    SortOrder.NAME_ASC -> filtered.sortedBy { it.name.lowercase() }
                    SortOrder.NAME_DESC -> filtered.sortedByDescending { it.name.lowercase() }
                    SortOrder.SIZE_DESC -> filtered.sortedByDescending { it.sizeBytes }
                    SortOrder.SIZE_ASC -> filtered.sortedBy { it.sizeBytes }
                }
                val summary = ScanSummary(
                    totalCount = scoped.size,
                    totalBytes = scoped.sumOf { it.sizeBytes },
                    newest = scoped.maxOfOrNull { it.lastModified } ?: 0L,
                    oldest = scoped.minOfOrNull { it.lastModified } ?: 0L,
                )
                VideoListState.Loaded(sorted, summary, refreshing)
            }
        }
    }
}

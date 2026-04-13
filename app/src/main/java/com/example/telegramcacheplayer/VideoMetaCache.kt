package com.example.telegramcacheplayer

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * In-memory LRU cache of video metadata (duration + scaled first-frame bitmap).
 * Extraction runs on a bounded IO pool so the scroll list doesn't overwhelm the
 * device when the user scrolls fast through a long list.
 */
object VideoMetaCache {

    data class Meta(val durationMs: Long, val thumb: Bitmap?)

    private val cache = object : LruCache<String, Meta>(200) {
        override fun sizeOf(key: String, value: Meta): Int = 1
    }

    // Bound concurrent extractor count to avoid CPU / memory spikes.
    private val extractPermits = Semaphore(permits = 3)

    fun getCached(path: String): Meta? = cache[path]

    suspend fun load(file: File): Meta {
        cache[file.path]?.let { return it }
        return withContext(Dispatchers.IO) {
            extractPermits.withPermit {
                cache[file.path]?.let { return@withPermit it }
                val meta = runCatching { extract(file) }
                    .getOrElse { Meta(0L, null) }
                cache.put(file.path, meta)
                meta
            }
        }
    }

    private fun extract(file: File): Meta {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            // Grab a frame near the start but not at 0 (some files have a black
            // first frame). 500ms is usually well after fade-in.
            val frameUs = (minOf(durationMs, 500L) * 1000L).coerceAtLeast(0L)
            val bitmap: Bitmap? = runCatching {
                retriever.getScaledFrameAtTime(
                    frameUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    /* dstWidth = */ 320,
                    /* dstHeight = */ 180,
                )
            }.getOrNull() ?: runCatching {
                retriever.getFrameAtTime(frameUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }.getOrNull()
            return Meta(durationMs, bitmap)
        } finally {
            runCatching { retriever.release() }
        }
    }
}

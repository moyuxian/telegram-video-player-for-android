package com.example.telegramcacheplayer

import android.util.Log
import java.io.File

/**
 * Scans a directory tree for video files using raw java.io.File.
 *
 * The root path embeds U+200B inside "Android" to bypass the FUSE-level
 * scoped-storage restriction on /sdcard/Android/data. See ZwspAccessTest for
 * the empirical verification on HyperOS / Android 15.
 *
 * Requires MANAGE_EXTERNAL_STORAGE (All Files Access).
 */
object TelegramFileScanner {
    private const val TAG = "TgScanner"
    private const val ZWSP = "\u200B"

    private const val SDCARD_ZWSP = "/storage/emulated/0/A${ZWSP}ndroid/data"

    /** Telegram package names we know about. Add more if needed. */
    private val TELEGRAM_PACKAGES = listOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.telegram.plus",
        "nekox.messenger",
    )

    /** Primary root shown in the UI. Kept for backwards display compatibility. */
    const val TELEGRAM_ROOT =
        "$SDCARD_ZWSP/org.telegram.messenger.web/files/Telegram"

    /** All candidate roots that scan() walks. Non-existent ones are silently skipped. */
    val CANDIDATE_ROOTS: List<String> = TELEGRAM_PACKAGES.map {
        "$SDCARD_ZWSP/$it/files/Telegram"
    }

    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm")

    data class Video(
        val file: File,
        val name: String,
        val sizeBytes: Long,
        val lastModified: Long,
        val relativePath: String,
    )

    fun scan(roots: List<String> = CANDIDATE_ROOTS): List<Video> {
        val out = mutableListOf<Video>()
        for (rootPath in roots) {
            val root = File(rootPath)
            if (!root.exists()) continue
            if (!root.isDirectory) {
                Log.w(TAG, "not a directory: $rootPath")
                continue
            }
            walk(root, root, out, depth = 0, maxDepth = 10)
        }
        out.sortByDescending { it.lastModified }
        return out
    }

    private fun walk(
        root: File,
        dir: File,
        out: MutableList<Video>,
        depth: Int,
        maxDepth: Int,
    ) {
        if (depth > maxDepth) return
        val children = try {
            dir.listFiles()
        } catch (t: Throwable) {
            Log.w(TAG, "listFiles failed for ${dir.path}: ${t.message}")
            return
        } ?: return

        for (child in children) {
            if (child.isDirectory) {
                walk(root, child, out, depth + 1, maxDepth)
            } else if (child.isFile) {
                val name = child.name
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext in VIDEO_EXTENSIONS) {
                    val rel = child.path.removePrefix(root.path).removePrefix("/")
                    out += Video(
                        file = child,
                        name = name,
                        sizeBytes = child.length(),
                        lastModified = child.lastModified(),
                        relativePath = rel,
                    )
                }
            }
        }
    }
}

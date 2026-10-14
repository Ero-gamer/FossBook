package com.eepiemi.materialbook.utils

import java.io.File

/**
 * Photos and videos shared into the app from another app's share sheet ("Post to Facebook",
 * "Facebook story": the ShareToFeed and ShareToStory entries in the manifest). They go into
 * Facebook's own composer, where the user writes the caption and posts; nothing is posted by the
 * app. See share_media.js for how the page takes them.
 *
 * - Feed: m.facebook.com's composer, which takes photos only, several at once.
 * - Story: the desktop site's story creator (m.facebook.com sends stories to the Facebook app),
 *   in the Messages layer's desktop WebView; it takes one photo or video at a time, so several
 *   are offered one story after another.
 */
enum class ShareTarget { FEED, STORY }

/** A shared file, copied into the app's cache so it outlives the sharing app's permission. */
data class SharedFile(val file: File, val name: String, val mimeType: String)

/** A shared item as the sharing app describes it, before it's copied. */
data class ShareCandidate(val name: String, val mimeType: String, val size: Long)

/** What a share asked for and what the app can hand on. */
data class ShareSelection(val accepted: List<ShareCandidate>, val skipped: Int)

/** The page Facebook creates stories on (desktop site; not a section that closes the layer). */
const val STORY_CREATE_URL = "https://www.facebook.com/stories/create"

/** The mobile Home feed, where the composer's Photo button is. */
const val FEED_COMPOSER_PAGE_URL = "https://m.facebook.com/"

object ShareMedia {
    const val MAX_FEED_PHOTOS = 30
    const val MAX_STORY_ITEMS = 10
    const val MAX_PHOTO_BYTES = 40L * 1024 * 1024
    const val MAX_VIDEO_BYTES = 250L * 1024 * 1024

    private const val FEED_ALIAS = ".ShareToFeed"
    private const val STORY_ALIAS = ".ShareToStory"

    /** The target of a share that arrived through [componentClassName] (one of the aliases). */
    fun targetOf(componentClassName: String?): ShareTarget? = when {
        componentClassName == null -> null
        componentClassName.endsWith(FEED_ALIAS) -> ShareTarget.FEED
        componentClassName.endsWith(STORY_ALIAS) -> ShareTarget.STORY
        else -> null
    }

    /**
     * The items [target] takes, in order, within its count and size limits: the feed's composer
     * takes photos, the story creator photos and videos. A size of -1 (unknown) passes; the copy
     * enforces the limit then.
     */
    fun select(target: ShareTarget, candidates: List<ShareCandidate>): ShareSelection {
        val fitting = candidates.filter { fits(target, it.mimeType, it.size) }
        val max = if (target == ShareTarget.FEED) MAX_FEED_PHOTOS else MAX_STORY_ITEMS
        val accepted = fitting.take(max)
        return ShareSelection(accepted, candidates.size - accepted.size)
    }

    /** Whether a file of [mimeType] and [size] bytes (-1: unknown) goes to [target]. */
    fun fits(target: ShareTarget, mimeType: String, size: Long): Boolean {
        val type = mimeType.lowercase()
        val photo = type.startsWith("image/")
        val video = type.startsWith("video/")
        if (!photo && !(video && target == ShareTarget.STORY)) return false
        val max = if (photo) MAX_PHOTO_BYTES else MAX_VIDEO_BYTES
        return size < 0 || size in 1..max
    }

    /** The largest a copy of [mimeType] may grow to. */
    fun maxBytes(mimeType: String): Long =
        if (mimeType.lowercase().startsWith("video/")) MAX_VIDEO_BYTES else MAX_PHOTO_BYTES

    /** A file name safe to keep in the cache and give the page: letters, digits, '.', '-', '_'. */
    fun safeName(name: String, index: Int, mimeType: String): String {
        val cleaned = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_')
        if (cleaned.isNotEmpty() && cleaned.length <= 80) return "${index}_$cleaned"
        val extension = mimeType.substringAfter('/', "").substringBefore(';').filter { it.isLetterOrDigit() }
        return "${index}_shared" + if (extension.isNotEmpty()) ".$extension" else ""
    }
}

/**
 * The files of the share in progress, handed to the page one at a time (story) or all at once
 * (feed) through share_media.js. One share at a time: a new one replaces it.
 */
object ShareMediaQueue {
    private var target: ShareTarget? = null
    private var files: List<SharedFile> = emptyList()
    private var next = 0

    @Synchronized
    fun start(newTarget: ShareTarget, newFiles: List<SharedFile>) {
        target = newTarget
        files = newFiles
        next = 0
    }

    @Synchronized
    fun pending(forTarget: ShareTarget): Boolean = target == forTarget && next < files.size

    /** The next file for [forTarget], or null when there's none left. */
    @Synchronized
    fun take(forTarget: ShareTarget): SharedFile? {
        if (target != forTarget || next >= files.size) return null
        return files[next++]
    }

    /** All the files left for [forTarget] (the feed's composer takes them at once). */
    @Synchronized
    fun takeAll(forTarget: ShareTarget): List<SharedFile> {
        if (target != forTarget) return emptyList()
        val rest = files.drop(next)
        next = files.size
        return rest
    }

    @Synchronized
    fun remaining(forTarget: ShareTarget): Int = if (target == forTarget) files.size - next else 0

    @Synchronized
    fun clear() {
        target = null
        files = emptyList()
        next = 0
    }
}

/** Whether [url] is the desktop story creator, where a story share's files are offered. */
fun isStoryCreatePage(url: String?): Boolean {
    val uri = url?.let { runCatching { java.net.URI(it) }.getOrNull() } ?: return false
    return uri.host?.lowercase() == "www.facebook.com" && (uri.path ?: "").startsWith("/stories/create")
}

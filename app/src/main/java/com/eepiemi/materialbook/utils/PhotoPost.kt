package com.eepiemi.materialbook.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The post a photo belongs to: its m.facebook.com address and the photo's place among the
 * photos its tiles show (0 is the first).
 *
 * A memory in the feed ("5 years ago") opens each photo of a multi-photo post on its own
 * (photo.php?fbid=…, "1 of 1"), so the others can't be reached from it; the mobile site has no
 * trace of the post there. Facebook's desktop page for the same photo names it: its tracking data
 * holds the post, its owner and the photos the post shows
 * (`"top_level_post_id":"…","content_owner_id_new":…,"photo_attachments_list":["…",…]`; a Page's
 * post has its `"page_id"` before the list).
 */
data class PhotoPost(val url: String, val index: Int) {

    fun toJson(fbid: String): String =
        JSONObject().put("fbid", fbid).put("url", url).put("index", index).toString()

    companion object {
        private val TRACKING = Regex(
            """"top_level_post_id":"(\d+)","content_owner_id_new":"?(\d+)"?,(?:"[a-z_]+":"?\d+"?,)*""" +
                """"photo_attachments_list":\[([^\]]*)]"""
        )
        private val ID = Regex("""\d+""")

        /**
         * The post of photo [fbid] from its desktop page [html], or null when the page names no
         * post with several photos. A photo past the ones the post's tiles show (behind "+6")
         * isn't in the list: it opens at the last tile, the one before it.
         */
        fun parse(html: String, fbid: String): PhotoPost? {
            // The tracking data is a JSON string inside the page's JSON: its quotes are escaped.
            val text = html.replace("\\\"", "\"")
            var fallback: PhotoPost? = null
            for (match in TRACKING.findAll(text)) {
                val (post, owner, list) = match.destructured
                val ids = ID.findAll(list).map { it.value }.toList()
                if (ids.size < 2) continue
                val url = "https://m.facebook.com/$owner/posts/$post/"
                val index = ids.indexOf(fbid)
                if (index >= 0) return PhotoPost(url, index)
                if (fallback == null) fallback = PhotoPost(url, ids.lastIndex)
            }
            return fallback
        }

        fun isPhotoId(fbid: String) = fbid.length in 1..MAX_ID_LENGTH && fbid.all { it.isDigit() }

        private const val MAX_ID_LENGTH = 25
    }
}

private const val TAG = "Astryxbook"
private const val COOKIE_URL = "https://www.facebook.com"
private const val TIMEOUT_MS = 20_000

private val lookups = Executors.newSingleThreadExecutor()
private val main = Handler(Looper.getMainLooper())

/**
 * Looks up the post of photo [fbid] off the main thread ([PhotoPost]) and hands [reply] its JSON
 * on the main thread, or nothing when there's none (or on any error).
 */
fun lookUpPhotoPost(context: Context, fbid: String, reply: (String) -> Unit) {
    if (!PhotoPost.isPhotoId(fbid)) return
    lookups.execute {
        val post = fetchDesktopPhotoPage(context, fbid)?.let { PhotoPost.parse(it, fbid) }
        Log.d(TAG, "Photo post of $fbid: ${post ?: "none"}")
        if (post != null) main.post { reply(post.toJson(fbid)) }
    }
}

// The desktop photo page, fetched with the app's Facebook login (the WebView's cookies) and the
// headers a browser sends when it opens a page (with fewer, Facebook answers 400). Cookies
// Facebook sets in the answer aren't kept: this is a lookup, not a visit.
private fun fetchDesktopPhotoPage(context: Context, fbid: String): String? = runCatching {
    val cookie = CookieManager.getInstance().getCookie(COOKIE_URL)
    if (cookie == null || !cookie.contains("c_user=")) return null
    val connection = URL("$COOKIE_URL/photo.php?fbid=$fbid").openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Cookie", cookie)
        connection.setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        connection.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
        connection.setRequestProperty("Upgrade-Insecure-Requests", "1")
        connection.setRequestProperty("Sec-Fetch-Dest", "document")
        connection.setRequestProperty("Sec-Fetch-Mode", "navigate")
        connection.setRequestProperty("Sec-Fetch-Site", "none")
        connection.setRequestProperty("Sec-Fetch-User", "?1")
        connection.setRequestProperty("Sec-CH-UA-Mobile", "?0")
        connection.setRequestProperty("Sec-CH-UA-Platform", "\"Windows\"")
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            Log.d(TAG, "Photo post of $fbid: HTTP ${connection.responseCode}")
            return null
        }
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}.onFailure { Log.w(TAG, "Photo post of $fbid: fetch failed", it) }.getOrNull()

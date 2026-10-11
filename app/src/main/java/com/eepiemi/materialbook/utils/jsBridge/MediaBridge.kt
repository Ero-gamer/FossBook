package com.eepiemi.materialbook.utils.jsBridge

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** The object page scripts post media to (download, copy to clipboard); see [installMediaBridge]. */
const val MEDIA_BRIDGE_NAME = "AstryxMedia"

/** Facebook's and Messenger's own pages: the only ones that get [MEDIA_BRIDGE_NAME]. */
val FACEBOOK_ORIGIN_RULES = setOf(
    "https://facebook.com", "https://*.facebook.com",
    "https://messenger.com", "https://*.messenger.com"
)

/** One message from a page script: `<action>\n<MIME type>\n<data: URL>`. */
data class MediaRequest(val action: String, val mimeType: String, val dataUrl: String) {
    companion object {
        const val DOWNLOAD = "download"
        const val COPY = "copy"

        fun parse(message: String?): MediaRequest? {
            val parts = message?.split('\n', limit = 3) ?: return null
            if (parts.size != 3) return null
            val (action, mimeType, dataUrl) = parts
            if (action != DOWNLOAD && action != COPY) return null
            return MediaRequest(action, mimeType, dataUrl)
        }
    }
}

// Decoding and saving can take a while for a video: off the main thread, on a thread with
// a Looper (the bridges show Toasts), as the JS interface's own thread is.
private val mediaHandler by lazy {
    Handler(HandlerThread("AstryxMedia").apply { start() }.looper)
}

/**
 * Download and copy-to-clipboard for the page scripts. A JS interface is visible to every
 * frame of every page, ads and embeds included, so any of them could save a file to
 * Downloads; this listener only takes messages from the main frame of a Facebook or
 * Messenger page. On a WebView too old for message listeners (before 82), the bridges are
 * added as JS interfaces as before, and the scripts use those.
 */
fun installMediaBridge(webView: WebView, context: Context) {
    val download = DownloadBridge(context)
    val clipboard = ClipboardBridge(context)
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        WebViewCompat.addWebMessageListener(
            webView,
            MEDIA_BRIDGE_NAME,
            FACEBOOK_ORIGIN_RULES,
            WebViewCompat.WebMessageListener { _, message, _, isMainFrame, _ ->
                val request = if (isMainFrame) MediaRequest.parse(message.data) else null
                if (request != null) {
                    mediaHandler.post {
                        when (request.action) {
                            MediaRequest.DOWNLOAD -> download.downloadBase64File(request.dataUrl, request.mimeType)
                            MediaRequest.COPY -> clipboard.copyImageToClipboard(request.dataUrl, request.mimeType)
                        }
                    }
                }
            }
        )
    } else {
        webView.addJavascriptInterface(download, "DownloadBridge")
        webView.addJavascriptInterface(clipboard, "ClipboardBridge")
    }
}

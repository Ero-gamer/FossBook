package com.eepiemi.materialbook.ui.screens

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eepiemi.materialbook.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// evaluateJavascript returns strings JSON-quoted.
private fun String.unquoted() = removeSurrounding("\"")

/**
 * Which media the download button of download_content.js saves, against a real WebView (same
 * harness approach as ReelAdsJsTest). fetch is replaced by a stub that records the address, so
 * nothing is downloaded. The fixtures mimic m.facebook.com's players: a <video> with a blob:
 * source inside data-mcomponent="MVideo", which carries the whole MP4 in data-video-url.
 */
@RunWith(AndroidJUnit4::class)
class MediaDownloadJsTest {

    private class Harness {
        lateinit var webView: WebView
            private set

        fun load(url: String, body: String) {
            val latch = CountDownLatch(1)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    layout(0, 0, 1080, 2000)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            latch.countDown()
                        }
                    }
                    loadDataWithBaseURL(
                        url, "<html><head></head><body style='margin:0'>$body</body></html>",
                        "text/html", "UTF-8", null
                    )
                }
            }
            assertTrue("page never finished loading", latch.await(10, TimeUnit.SECONDS))
            eval(
                "window.__fetched = []; window.fetch = function (u) { window.__fetched.push(String(u));" +
                    " return Promise.resolve(new Response(new Blob(['x'], { type: 'video/mp4' }))); };" +
                    "window.AstryxDownloads = { postMessage: function () {} };" +
                    "window.isFeed = function () { return false; };"
            )
            val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
            eval(resources.openRawResource(R.raw.download_content).bufferedReader().use { it.readText() })
        }

        fun eval(js: String): String {
            var result: String? = null
            val latch = CountDownLatch(1)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                webView.evaluateJavascript(js) { value ->
                    result = value
                    latch.countDown()
                }
            }
            assertTrue("evaluateJavascript never returned", latch.await(10, TimeUnit.SECONDS))
            return result ?: "null"
        }

        /** Presses the download button; the address fetched. */
        fun download(): String {
            eval("document.getElementById('materialbook-global-downloader').click()")
            Thread.sleep(200)
            return eval("window.__fetched.join(' ')").unquoted()
        }
    }

    // A player as tall as [heightVh]% of the screen, with a playing blob: video.
    private fun player(id: String, heightVh: Int) =
        "<div data-mcomponent='MVideo' data-video-url='https://video.example/$id.mp4' style='height:${heightVh}vh'>" +
            "<video id='$id' style='width:100%;height:100%' src='blob:https://m.facebook.com/$id'></video></div>"

    // A still image, fully on screen.
    private val still = "<img src='https://scontent.fbcdn.net/still.jpg' style='display:block;width:300px;height:300px'>"

    @Test
    fun aReelTallerThanTheScreen_downloadsItsVideo_notAStill() {
        val h = Harness()
        // The reel on screen is taller than the window; the next one waits below it.
        h.load("https://m.facebook.com/reel/1/", still + player("current", 120) + player("next", 120))
        h.eval("window.scrollTo(0, 300)")
        assertEquals("https://video.example/current.mp4", h.download())
    }

    @Test
    fun theReelMostlyOnScreen_isTheOneDownloaded_notTheFirstInThePage() {
        val h = Harness()
        h.load("https://m.facebook.com/reel/1/", player("previous", 100) + player("current", 100))
        // Scrolled to the second reel: the first is above the screen.
        h.eval("window.scrollTo(0, document.getElementById('current').getBoundingClientRect().top + window.scrollY)")
        assertEquals("https://video.example/current.mp4", h.download())
    }

    @Test
    fun aPhotoOnScreen_isDownloadedWhenThereIsNoVideo() {
        val h = Harness()
        h.load("https://m.facebook.com/photo.php?fbid=1", still)
        assertEquals("https://scontent.fbcdn.net/still.jpg", h.download())
    }
}

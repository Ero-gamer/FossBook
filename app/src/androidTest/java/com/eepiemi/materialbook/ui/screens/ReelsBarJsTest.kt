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

/**
 * The Reels bar: Facebook's blue "Open app" button (hidden by scripts.js, which always runs; it
 * used to be hidden by download_content.js only, with Download on) and the copy button
 * (copy_to_clipboard.js), which a Like on a reel brought up although a reel has nothing to copy.
 */
@RunWith(AndroidJUnit4::class)
class ReelsBarJsTest {

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
                        url,
                        "<html><head><meta name='viewport' content='width=device-width'></head>" +
                            "<body style='margin:0'>$body</body></html>",
                        "text/html", "UTF-8", null
                    )
                }
            }
            assertTrue("page never finished loading", latch.await(10, TimeUnit.SECONDS))
        }

        fun run(script: Int) {
            val text = InstrumentationRegistry.getInstrumentation().targetContext.resources
                .openRawResource(script).bufferedReader().use { it.readText() }
            eval(text)
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

        fun waitFor(js: String, ms: Long = 5000): Boolean {
            val until = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < until) {
                if (eval(js) == "true") return true
                Thread.sleep(100)
            }
            return false
        }
    }

    @Test
    fun openApp_isHidden_withoutTheDownloadScript() {
        val h = Harness()
        h.load("https://m.facebook.com/reel/123", REELS_BAR)
        h.run(R.raw.scripts)
        // Facebook adds it once the page has drawn.
        h.eval(ADD_OPEN_APP)
        assertTrue(h.waitFor("getComputedStyle(document.getElementById('open-app')).display === 'none'"))
        assertEquals("\"block\"", h.eval("getComputedStyle(document.getElementById('search')).display"))
    }

    @Test
    fun theCopyButton_staysHiddenOnReels() {
        // A large Facebook image on screen, the way a Like's animation or a reel's poster puts one.
        val h = Harness()
        h.load("https://m.facebook.com/reel/123", "<div class='story_viewer'>" + BIG_IMAGE + "</div>")
        h.run(R.raw.copy_to_clipboard)
        Thread.sleep(800)
        assertEquals("false", h.eval("document.getElementById('materialbook-clipboard-copier').classList.contains('visible')"))
    }

    @Test
    fun theCopyButton_stillShowsOnAPhoto() {
        val h = Harness()
        h.load("https://m.facebook.com/photo.php", BIG_IMAGE)
        h.run(R.raw.copy_to_clipboard)
        assertTrue(h.waitFor("document.getElementById('materialbook-clipboard-copier').classList.contains('visible')"))
    }

    private companion object {
        const val REELS_BAR =
            "<div id='bar'><div role='button' id='search'><span>S</span></div></div>"

        // Facebook's Open app button: its icon (U+F196C) and label in a div.fl.ac.
        const val ADD_OPEN_APP =
            "(function () { var b = document.createElement('div'); b.setAttribute('role', 'button'); b.id = 'open-app';" +
                " b.innerHTML = \"<div class='fl ac'><span></span><span>Open app</span></div>\";" +
                " b.querySelector('span').textContent = String.fromCodePoint(0xF196C);" +
                " document.getElementById('bar').appendChild(b); })()"

        const val BIG_IMAGE =
            "<img src='https://scontent.fbcdn.net/a.jpg' width='300' height='300' style='display:block;width:300px;height:300px'>"
    }
}

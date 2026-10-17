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
 * scripts.js presses "Not now" on Facebook's "Facebook is better on the app" sheet, which comes
 * up over the feed when the app opens. The fixture is that sheet as read from the phone
 * (2026-10-17): a dialog screen with a bottom sheet holding the Facebook logo, a title, a line of
 * text and the two buttons. A sheet that comes up right after a tap is the user's own and stays.
 */
@RunWith(AndroidJUnit4::class)
class AppPromptJsTest {

    private class Harness {
        lateinit var webView: WebView
            private set

        fun load() {
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
                        "https://m.facebook.com/",
                        "<html><head><meta name='viewport' content='width=device-width'></head>" +
                            "<body style='margin:0'><div id='feed'>feed</div></body></html>",
                        "text/html", "UTF-8", null
                    )
                }
            }
            assertTrue("page never finished loading", latch.await(10, TimeUnit.SECONDS))
            val scripts = InstrumentationRegistry.getInstrumentation().targetContext.resources
                .openRawResource(R.raw.scripts).bufferedReader().use { it.readText() }
            eval(scripts)
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

        fun waitFor(js: String, ms: Long = 3000): Boolean {
            val until = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < until) {
                if (eval(js) == "true") return true
                Thread.sleep(100)
            }
            return false
        }

        // Facebook adds the sheet's screen to the page; its buttons record a press.
        fun addSheet(extra: String = "", sheet: String = SHEET) {
            eval(
                "window.__pressed = null; var d = document.createElement('div'); d.innerHTML = \"$sheet\".replace('EXTRA', \"$extra\");" +
                    " document.body.appendChild(d.firstChild);" +
                    " document.querySelectorAll('[role=button]').forEach(function (b) {" +
                    " b.addEventListener('click', function () { window.__pressed = b.getAttribute('aria-label'); }); }); 1"
            )
        }
    }

    @Test
    fun theAppSheet_isPressedAwayWithNotNow() {
        val h = Harness()
        h.load()
        h.addSheet()
        assertTrue(h.waitFor("window.__pressed === 'Not now'"))
    }

    @Test
    fun theEnglishSheet_isPressedAway_evenRightAfterATap() {
        // Its words name it for sure.
        val h = Harness()
        h.load()
        h.eval("document.getElementById('feed').dispatchEvent(new PointerEvent('pointerdown', {bubbles: true})); 1")
        h.addSheet()
        assertTrue(h.waitFor("window.__pressed === 'Not now'"))
    }

    @Test
    fun theHebrewSheet_isPressedAway_evenRightAfterATap() {
        // Known by its "Not now" button; its title isn't (a stand-in here).
        val h = Harness()
        h.load()
        h.eval("document.getElementById('feed').dispatchEvent(new PointerEvent('pointerdown', {bubbles: true})); 1")
        h.addSheet(sheet = sheet("\u05E4\u05D9\u05D9\u05E1\u05D1\u05D5\u05E7 \u05D8\u05D5\u05D1 \u05D9\u05D5\u05EA\u05E8 \u05D1\u05D0\u05E4\u05DC\u05D9\u05E7\u05E6\u05D9\u05D4", "\u05E7\u05D1\u05DC \u05D0\u05EA \u05D4\u05D0\u05E4\u05DC\u05D9\u05E7\u05E6\u05D9\u05D4", HE_NOT_NOW))
        assertTrue(h.waitFor("window.__pressed === '$HE_NOT_NOW'"))
    }

    @Test
    fun aSheetInAnotherLanguage_isPressedAwayWithItsSecondButton() {
        val h = Harness()
        h.load()
        h.addSheet(sheet = sheet("Facebook ist in der App besser", "App holen", "Jetzt nicht"))
        assertTrue(h.waitFor("window.__pressed === 'Jetzt nicht'"))
    }

    @Test
    fun anUnknownSheetRightAfterATap_isLeftToTheUser() {
        // A confirmation the user opened, shaped like the app sheet.
        val h = Harness()
        h.load()
        h.eval("document.getElementById('feed').dispatchEvent(new PointerEvent('pointerdown', {bubbles: true})); 1")
        h.addSheet(sheet = sheet("Delete post?", "Delete", "Cancel"))
        Thread.sleep(800)
        assertEquals("null", h.eval("window.__pressed"))
    }

    @Test
    fun aSheetToTypeIn_isLeftAlone() {
        val h = Harness()
        h.load()
        h.addSheet("<textarea></textarea>")
        Thread.sleep(800)
        assertEquals("null", h.eval("window.__pressed"))
    }

    private companion object {
        fun sheet(title: String, first: String, second: String) =
            "<div class='m bg-s1 dark-mode dialog-screen' data-mcomponent='MScreen' style='position:fixed;top:0;left:0;right:0;bottom:0'>" +
                "<div class='m fixed-container bottom' data-mcomponent='MContainer' data-actual-height='339' style='position:fixed;bottom:0;left:0;right:0'>" +
                "<div data-mcomponent='ServerImageArea'><img style='width:54px;height:54px'></div>" +
                "<h1><span>$title</span></h1>" +
                "<div><span>Get the latest updates, newest features and the fastest experience.</span></div>EXTRA" +
                "<div role='button' aria-label='$first' data-action-id='32755'><span>$first</span></div>" +
                "<div role='button' aria-label='$second' data-action-id='32753'><span>$second</span></div>" +
                "</div></div>"

        const val HE_NOT_NOW = "\u05DC\u05D0 \u05E2\u05DB\u05E9\u05D9\u05D5"

        // Facebook breaks the title over two lines ("...better on the" / "app").
        val SHEET = sheet("Facebook is better on the</span> <span>app", "Get app", "Not now")
    }
}

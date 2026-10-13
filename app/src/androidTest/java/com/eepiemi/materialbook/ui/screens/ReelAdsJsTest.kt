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
 * Tests for the Reels part of adblock.js against a real WebView, same harness approach as
 * BackHandlerJsTest. Each fixture reel is a `div.vertically-snappable`, as on m.facebook.com's
 * Reels viewer; a hidden ad is marked `data-ad-hidden="true"`.
 */
@RunWith(AndroidJUnit4::class)
class ReelAdsJsTest {

    private fun String.unquoted() = removeSurrounding("\"")

    private class Harness {
        lateinit var webView: WebView
            private set

        fun load(body: String) {
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
                        "https://m.facebook.com/reel/1",
                        "<html><body>$body</body></html>",
                        "text/html", "UTF-8", null
                    )
                }
            }
            assertTrue("page never finished loading", latch.await(10, TimeUnit.SECONDS))
            // The mobile site (scripts.js defines this for the page scripts).
            eval("window.isDesktopMode = () => false;")
            val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
            eval(resources.openRawResource(R.raw.adblock).bufferedReader().use { it.readText() })
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

        fun hidden(vararg ids: String): String = eval(
            ids.joinToString(",", "[", "]") { "'$it'" } +
                ".map(id => document.getElementById(id).dataset.adHidden === 'true').join(',')"
        ).unquoted()
    }

    private fun reel(id: String, vararg spans: String) =
        "<div class='vertically-snappable' id='$id' style='height:400px'>" +
            spans.joinToString("") { "<span>$it</span>" } + "</div>"

    @Test
    fun sponsoredLabel_inEveryScript_hidesTheReel() {
        val h = Harness()
        h.load(
            reel("en", "Some Page", "Sponsored") +
                reel("he", "עמוד כלשהו", "ממומן") +
                reel("ru", "Страница", "Реклама") +
                reel("ja", "ページ", "広告") +
                reel("hi", "पेज", "प्रायोजित")
        )

        assertEquals("true,true,true,true,true", h.hidden("en", "he", "ru", "ja", "hi"))
    }

    @Test
    fun labelInsideAnotherWord_isNotAnAd() {
        val h = Harness()
        h.load(
            reel("en", "Add friend") +
                reel("he", "תכנים ממומנים בקרוב") +
                reel("ru", "Рекламами") +
                reel("ja", "広告主の話")
        )

        assertEquals("false,false,false,false", h.hidden("en", "he", "ru", "ja"))
    }

    @Test
    fun reelAddedLater_isCheckedToo() {
        val h = Harness()
        h.load("<div id='feed'>${reel("first", "A friend's reel")}</div>")
        h.eval(
            "document.getElementById('feed').insertAdjacentHTML('beforeend', " +
                "\"${reel("later", "Pagina", "Sponsorizzato")}\");"
        )

        // The MutationObserver has run by now: its callback is a microtask of the insert.
        assertEquals("false,true", h.hidden("first", "later"))
    }
}

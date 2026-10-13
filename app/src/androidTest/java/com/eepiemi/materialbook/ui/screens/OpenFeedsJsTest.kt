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
 * Tests for open_feeds.js ("Start on Feeds") and the Back handling of the Feeds it opens
 * (isStartFeeds in scripts.js), against a real WebView, same harness approach as ReelAdsJsTest.
 * The fixture is m.facebook.com's menu: a Feeds entry (a role="button" with Facebook's Feeds
 * icon) whose press shows Feeds at "/" with history.replaceState, as the real one does.
 */
@RunWith(AndroidJUnit4::class)
class OpenFeedsJsTest {

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
                    loadDataWithBaseURL(url, "<html><head></head><body>$body</body></html>", "text/html", "UTF-8", null)
                }
            }
            assertTrue("page never finished loading", latch.await(10, TimeUnit.SECONDS))
        }

        fun runOpenFeeds() = eval(raw(R.raw.open_feeds))

        // The desktop/feed identifiers and the back handler, i.e. scripts.js up to the first
        // block that doesn't belong to them (as in BackHandlerJsTest).
        fun runBackHandler() {
            val all = raw(R.raw.scripts)
            val end = all.indexOf("// Enable press and hold")
            assertTrue("back handler block not found in scripts.js", end > 0)
            eval(all.substring(0, end))
        }

        private fun raw(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.resources
            .openRawResource(id).bufferedReader().use { it.readText() }

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

        /** Polls [js] until it's "true", for up to [ms]. */
        fun waitFor(js: String, ms: Long = 3000): Boolean {
            val until = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < until) {
                if (eval(js) == "true") return true
                Thread.sleep(50)
            }
            return false
        }

        fun bodyHidden() = eval("getComputedStyle(document.body).visibility === 'hidden'") == "true"
    }

    // The menu's Feeds entry; [pressesToOpen] presses until the page handles it.
    private fun feedsEntry(pressesToOpen: Int = 1, icon: Boolean = true, label: String = "פידים") =
        "<div role='button' id='feeds' aria-label='$label' onclick=\"window.__presses = (window.__presses || 0) + 1;" +
            " if (window.__presses >= $pressesToOpen) history.replaceState(null, '', '/');\">" +
            (if (icon) "<img src='https://static.xx.fbcdn.net/rsrc.php/y0/r/FTDJiLwLrcM.webp'>" else "") +
            "<span>$label</span></div>"

    private val menu = "https://m.facebook.com/bookmarks/#astryx-feeds"

    @Test
    fun feedsEntry_isPressed_byItsIcon_inAnyLanguage() {
        val h = Harness()
        h.load(menu, feedsEntry())
        h.runOpenFeeds()
        assertTrue("Feeds never opened", h.waitFor("window.__astryxFeeds === true"))
        assertEquals("/", h.eval("location.pathname").unquoted())
        assertEquals(false, h.bodyHidden())
        assertEquals("1", h.eval("window.__presses"))
    }

    @Test
    fun feedsEntry_withoutTheIcon_isFoundByItsEnglishLabel() {
        val h = Harness()
        h.load(menu, feedsEntry(icon = false, label = "Feeds"))
        h.runOpenFeeds()
        assertTrue("Feeds never opened", h.waitFor("window.__astryxFeeds === true"))
    }

    @Test
    fun anEntryThatIgnoresTheFirstPress_isPressedAgain() {
        val h = Harness()
        h.load(menu, feedsEntry(pressesToOpen = 2))
        h.runOpenFeeds()
        assertTrue("Feeds never opened", h.waitFor("window.__astryxFeeds === true"))
        assertEquals("2", h.eval("window.__presses"))
    }

    @Test
    fun theMenuIsHidden_untilFeedsOpens() {
        val h = Harness()
        // No entry yet: the menu stays hidden while the script waits for it.
        h.load(menu, "<template id='later'>${feedsEntry()}</template>")
        h.runOpenFeeds()
        assertTrue(h.bodyHidden())
        h.eval("document.body.appendChild(document.getElementById('later').content.cloneNode(true))")
        assertTrue("Feeds never opened", h.waitFor("window.__astryxFeeds === true"))
        assertEquals(false, h.bodyHidden())
    }

    @Test
    fun aPageWithoutTheMark_isLeftAlone() {
        val h = Harness()
        h.load("https://m.facebook.com/bookmarks/", feedsEntry())
        h.runOpenFeeds()
        Thread.sleep(500)
        assertEquals("undefined", h.eval("typeof window.__presses").unquoted())
        assertEquals(false, h.bodyHidden())
    }

    @Test
    fun anotherPageInsteadOfTheMenu_isShown() {
        // Logged out, Facebook answers the menu with its login page.
        val h = Harness()
        h.load("https://m.facebook.com/login/#astryx-feeds", "<form id='login'></form>")
        h.runOpenFeeds()
        assertTrue("the page stayed hidden", h.waitFor("getComputedStyle(document.body).visibility !== 'hidden'"))
        assertEquals("undefined", h.eval("typeof window.__astryxFeeds").unquoted())
    }

    @Test
    fun back_onTheFeedsItOpened_leavesLikeOnTheFeed() {
        val h = Harness()
        h.load(menu, feedsEntry())
        h.runOpenFeeds()
        assertTrue("Feeds never opened", h.waitFor("window.__astryxFeeds === true"))
        h.runBackHandler()
        assertEquals("true", h.eval("window.isStartFeeds()"))
        assertEquals("exit", h.eval("window.backHandlerNB()").unquoted())
    }

    @Test
    fun back_isNotTakenOver_onOtherPagesAtTheRoot() {
        // Without the mark (Feeds opened from Facebook's own menu) Back works as before.
        val h = Harness()
        h.load("https://m.facebook.com/", "<div></div>")
        h.runBackHandler()
        assertEquals("false", h.eval("window.isStartFeeds()"))
        assertEquals("false", h.eval("window.backHandlerNB()").unquoted())
    }
}

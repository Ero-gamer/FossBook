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
 * Tests for story_layout.js (the desktop story creator laid out like the Facebook app's story
 * editor) against a real WebView. The fixture is built like Facebook's creator: a sidebar
 * (role=navigation) with the privacy button, the tools, the AI label row and Discard / Share,
 * beside the preview (role=main) whose story frame Facebook sizes from the window's height.
 */
@RunWith(AndroidJUnit4::class)
class StoryLayoutJsTest {

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
                        "https://www.facebook.com/stories/create",
                        "<html><head><meta name='viewport' content='width=device-width'></head>" +
                            "<body style='margin:0'>$body</body></html>",
                        "text/html", "UTF-8", null
                    )
                }
            }
            assertTrue("page never finished loading", latch.await(10, TimeUnit.SECONDS))
        }

        fun run() {
            val script = InstrumentationRegistry.getInstrumentation().targetContext.resources
                .openRawResource(R.raw.story_layout).bufferedReader().use { it.readText() }
            eval(script)
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

        fun waitFor(js: String, ms: Long = 6000): Boolean {
            val until = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < until) {
                if (eval(js) == "true") return true
                Thread.sleep(100)
            }
            return false
        }

        fun tag(selector: String) = eval("(document.querySelector('$selector') || {}).getAttribute ? " +
            "document.querySelector('$selector').getAttribute('data-astryx-story') : null").unquoted()

        fun hidden(selector: String) = eval("!!document.querySelector('$selector').closest('[data-astryx-story-hide]')")
    }

    @Test
    fun theStory_isTheLargest9to16FrameThatFits_andThePageGetsItsHeightBackAfterwards() {
        val h = Harness()
        h.load(CREATOR)
        h.run()
        h.eval("window.__showFrame()")

        // As wide as the column (less the frame's inset on each side), or as tall as the screen
        // above the bottom bar allows, whichever is smaller.
        val expected = "(function () { var box = document.getElementById('box');" +
            " var byWidth = box.clientWidth - 32;" +
            " var byHeight = (window.__realHeight - box.getBoundingClientRect().top - 16 - 68 - 64) * 9 / 16;" +
            " return Math.floor(Math.min(byWidth, byHeight)); })()"
        assertTrue(h.eval("JSON.stringify(window.__frameSize()) + ' wanted ' + $expected"), h.waitFor(
            "(function (s, w) { return Math.abs(s.width - w) <= 1 && Math.abs(s.boxHeight - (w * 16 / 9 + 68)) <= 2; })" +
                "(window.__frameSize(), $expected)"
        ))
        // The story is shared or discarded: the page gets its real height back.
        h.eval("window.__hideFrame()")
        assertTrue(h.waitFor("window.innerHeight === window.__realHeight"))
    }

    @Test
    fun aStoryInTheEditor_isLaidOutAtPhoneWidth_choosingOneAtTheDesktopWidthFitted() {
        val h = Harness()
        h.load(CREATOR)
        h.run()
        val viewport = "document.querySelector('meta[name=viewport]').content"
        assertTrue(h.eval(viewport), h.waitFor("/^width=600, initial-scale=0\\.\\d+/.test($viewport)"))
        h.eval("window.__showFrame()")
        assertTrue(h.eval(viewport), h.waitFor("$viewport.indexOf('width=device-width') === 0"))
    }

    @Test
    fun theTools_goOnTheStory_andPrivacyDiscardShare_inTheBottomBar() {
        val h = Harness()
        h.load(CREATOR)
        h.run()
        h.eval("window.__showFrame()")

        assertTrue(h.waitFor("document.querySelectorAll('[data-astryx-story=tool]').length === 3"))
        assertEquals("tools", h.tag("#tools"))
        assertEquals("actions", h.tag("#actions"))
        assertEquals("privacy", h.tag("#privacy"))
        assertEquals("main", h.tag("#main"))
        // The rest of the sidebar, and the narrow layout's own row under the story, are hidden.
        assertEquals("true", h.hidden("#title"))
        assertEquals("true", h.hidden("#ai"))
        assertEquals("true", h.hidden("#narrow-discard"))
        assertEquals("true", h.hidden("#narrow-privacy"))
        assertEquals("false", h.hidden("#frame"))
        // Other text buttons outside the sidebar (a panel's "See all") stay.
        assertEquals("false", h.hidden("#see-all"))
        // The tools sit on the story, above its bottom edge.
        assertTrue(h.waitFor(
            "document.getElementById('tools').getBoundingClientRect().bottom <= document.getElementById('frame').getBoundingClientRect().bottom"
        ))
        // Choosing a story again: Facebook's own page.
        h.eval("window.__hideFrame()")
        assertTrue(h.waitFor("!document.querySelector('[data-astryx-story]')"))
    }

    @Test
    fun facebooksOpenPopups_areCentred_closedOnesLeftAlone() {
        val h = Harness()
        h.load(CREATOR + POPUPS)
        h.run()
        h.eval("window.__showFrame()")
        assertTrue(h.waitFor("document.getElementById('open-popup').hasAttribute('data-astryx-story-popup')"))
        assertEquals("false", h.eval("document.getElementById('closed-popup').hasAttribute('data-astryx-story-popup')"))
    }

    @Test
    fun aPanelsIconButtons_areLabelled_andThePanelGetsADoneThatTapsTheStory() {
        val h = Harness()
        h.load(CREATOR + STICKER_PANEL)
        h.eval("window.__astryxStoryDone = 'Fertig';")
        h.run()
        h.eval("window.__showFrame()")

        assertTrue(h.waitFor("document.querySelectorAll('#styles [data-astryx-story-caption]').length === 4"))
        assertTrue(h.waitFor(
            "getComputedStyle(document.querySelector('#styles [role=button]'), '::after').content.indexOf('Large lyrics style') >= 0"
        ))
        // The clip bar has no text: labelled, but Facebook's own Done is enough.
        assertTrue(h.waitFor("document.querySelectorAll('#clip [data-astryx-story-caption]').length === 3"))
        assertEquals("0", h.eval("document.querySelectorAll('#clip-layer [data-astryx-story-done]').length"))

        assertTrue(h.waitFor("!!document.querySelector('#sticker-layer [data-astryx-story-done]')"))
        assertEquals("Fertig", h.eval("document.querySelector('[data-astryx-story-done]').textContent").unquoted())
        h.eval(RECORD_MOUSE)
        h.eval("document.querySelector('[data-astryx-story-done]').click()")
        assertTrue(h.eval("window.__mouse.join(' ')"), h.waitFor("window.__mouse.join(' ').indexOf('mousedown@') >= 0 && window.__mouse.join(' ').indexOf('mouseup@') >= 0"))
    }

    @Test
    fun aFingerDraggingAStickerOnTheStory_isReplayedAsAMouseDrag_aTapIsNot() {
        val h = Harness()
        h.load(CREATOR)
        h.run()
        h.eval("window.__showFrame()")
        assertTrue(h.waitFor("!!document.querySelector('[data-astryx-story=main]')"))
        h.eval(RECORD_MOUSE)

        h.eval(touch("touchstart", 100, 300) + touch("touchmove", 102, 301) + touch("touchend", 102, 301))
        assertEquals("tap", "", h.eval("window.__mouse.join(' ')").unquoted())

        h.eval(touch("touchstart", 100, 300) + touch("touchmove", 100, 330) + touch("touchmove", 100, 360) +
            touch("touchend", 100, 360))
        assertEquals(
            "pointerdown@100,300 mousedown@100,300 pointermove@100,330 mousemove@100,330 " +
                "pointermove@100,360 mousemove@100,360 pointerup@100,360 mouseup@100,360",
            h.eval("window.__mouse.join(' ')").unquoted()
        )
    }

    private companion object {
        // Facebook's creator: the sidebar (privacy in its header, the tools and the AI label row,
        // Discard / Share at its foot) beside the preview, whose 316 px column holds the frame.
        // Facebook sizes the frame (innerHeight - 256) x 9/16 wide on every resize. Its narrow
        // layout repeats privacy / Discard / Share outside the sidebar.
        const val CREATOR =
            "<div style='display:flex'>" +
                "<div role='navigation' id='nav' style='width:360px'>" +
                "<div id='header'><span id='title'>Your story</span>" +
                "<div role='button' id='privacy' aria-label='Story privacy settings'><svg width='20' height='20'></svg></div></div>" +
                "<div id='tools'>" +
                "<div role='button' class='t'><svg width='20' height='20'></svg><span>Add text</span></div>" +
                "<div role='button' class='t'><svg width='20' height='20'></svg><span>Add music</span></div>" +
                "<div role='button' class='t'><svg width='20' height='20'></svg><span>Alternative text</span></div>" +
                "<div role='button' id='ai' style='height:122px'><svg width='20' height='20'></svg><span>Add AI label</span></div>" +
                "</div>" +
                "<div id='actions'><div role='button'>Discard</div><div role='button'>Share to story</div></div>" +
                "</div>" +
                "<div role='main' id='main'>" +
                "<div id='narrow'><div role='button' id='narrow-privacy' aria-label='Story privacy settings'><svg width='20' height='20'></svg></div>" +
                "<div role='button' id='narrow-discard'>Discard</div><div role='button'>Share to story</div></div>" +
                "<div id='panel'><div role='button' id='see-all'>See all</div><div role='button'>Browse</div></div>" +
                "<div id='column' style='position:relative;width:316px;height:1500px;overflow:hidden'>" +
                "<div id='box' style='position:absolute;left:0;right:0;top:0;bottom:0'></div></div>" +
                "</div></div>" +
                "<script>window.__realHeight = window.innerHeight;" +
                "function __size() { var f = document.getElementById('frame'); if (!f) return;" +
                "  f.setAttribute('style', 'position: absolute; height: calc(100% - 68px); top: 16px; width: ' + ((window.innerHeight - 256) * 9 / 16) + 'px;'); }" +
                "window.addEventListener('resize', __size);" +
                "window.__showFrame = function () { document.getElementById('box').innerHTML =" +
                "  \"<div id='frame'><img width='10' height='10'></div>\"; __size(); };" +
                "window.__hideFrame = function () { document.getElementById('box').innerHTML = ''; };" +
                "window.__frameSize = function () { var f = document.getElementById('frame');" +
                "  return { width: Math.round(parseFloat(f.style.width)), boxHeight: document.getElementById('box').getBoundingClientRect().height }; };" +
                "</script>"

        // Facebook's popup hosts: fixed layers placed with translate(); a closed one keeps its
        // (hidden) content.
        const val POPUPS =
            "<div id='open-popup' style='position: fixed; transform: translate(355px, 157px);'><div>Search music</div></div>" +
                "<div id='closed-popup' style='position: fixed; transform: translate(355px, 157px);'>" +
                "<div style='visibility: hidden'><img width='10' height='10'>Hidden</div></div>"

        // A music sticker's panel (song, Remove, four style buttons named only for screen readers)
        // and the song clip bar (Pause, Undo, Done; no text). Facebook's fixed layers.
        const val STICKER_PANEL =
            "<div id='sticker-layer' style='position: fixed; transform: translate(40px, 300px);'><div style='background:#222;width:300px'>" +
                "<div>Hotel California</div><div role='button'>Remove</div>" +
                "<div id='styles' style='display:flex'>" +
                "<div role='button' aria-label='Large lyrics style' style='width:40px;height:40px'><svg width='20' height='20'></svg></div>" +
                "<div role='button' aria-label='Light lyrics style' style='width:40px;height:40px'><svg width='20' height='20'></svg></div>" +
                "<div role='button' aria-label='Dark lyrics style' style='width:40px;height:40px'><svg width='20' height='20'></svg></div>" +
                "<div role='button' aria-label='Floating lyrics style' style='width:40px;height:40px'><svg width='20' height='20'></svg></div>" +
                "</div></div></div>" +
                "<div id='clip-layer' style='position: fixed; transform: translate(10px, 700px);'><div id='clip' style='display:flex;width:360px'>" +
                "<div role='button' aria-label='Pause' style='width:40px;height:40px'><svg width='20' height='20'></svg></div>" +
                "<div style='width:300px;height:40px'></div>" +
                "<div role='button' aria-label='Undo' style='width:40px;height:40px'><svg width='20' height='20'></svg></div>" +
                "<div role='button' aria-label='Done' style='width:40px;height:40px'><svg width='20' height='20'></svg></div>" +
                "</div></div>"

        const val RECORD_MOUSE = "window.__mouse = [];" +
            "['pointerdown', 'mousedown', 'pointermove', 'mousemove', 'pointerup', 'mouseup'].forEach(function (t) {" +
            "  document.getElementById('frame').addEventListener(t, function (e) {" +
            "    if (e.pointerType === undefined || e.pointerType === 'mouse') window.__mouse.push(t + '@' + e.clientX + ',' + e.clientY); }); });"

        // A one-finger touch event on the story's frame, at (x, y).
        fun touch(type: String, x: Int, y: Int) =
            "(function () { var f = document.getElementById('frame');" +
                " var t = new Touch({ identifier: 1, target: f, clientX: $x, clientY: $y, screenX: $x, screenY: $y });" +
                " f.dispatchEvent(new TouchEvent('$type', { bubbles: true, cancelable: true, composed: true," +
                "   touches: '$type' === 'touchend' ? [] : [t], targetTouches: '$type' === 'touchend' ? [] : [t], changedTouches: [t] })); })();"
    }
}

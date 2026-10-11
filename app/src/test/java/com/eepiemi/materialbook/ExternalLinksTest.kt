package com.eepiemi.materialbook

import com.eepiemi.materialbook.utils.isLaunchableExternalUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which links the page may hand to another app (see openExternalUrl). */
class ExternalLinksTest {

    @Test
    fun webAndAppLinks_areLaunchable() {
        listOf(
            "https://example.com/",
            "http://example.com/a?b=c",
            "HTTPS://EXAMPLE.COM/",
            "mailto:someone@example.com",
            "tel:+15551234",
            "sms:+15551234",
            "geo:0,0?q=Tel+Aviv",
            "market://details?id=org.example",
            "whatsapp://send?text=hi",
        ).forEach { assertTrue(it, isLaunchableExternalUrl(it)) }
    }

    @Test
    fun localContentAndScriptUrls_areNotLaunchable() {
        listOf(
            "file:///sdcard/Download/x.apk",
            "content://com.astryx.book.fileprovider/clipboard_images/x.png",
            "javascript:alert(1)",
            "JavaScript:alert(1)",
            "data:text/html,<b>x</b>",
            "blob:https://www.facebook.com/abc",
            "about:blank",
            "android-app://com.example/https/example.com",
            "intent://x#Intent;scheme=fb;package=com.facebook.katana;end",
            "chrome://settings",
            "view-source:https://example.com/",
        ).forEach { assertFalse(it, isLaunchableExternalUrl(it)) }
    }

    @Test
    fun urlsWithoutAScheme_areNotLaunchable() {
        listOf("", "   ", "//example.com/", "/relative/path", "1http://x").forEach {
            assertFalse("'$it'", isLaunchableExternalUrl(it))
        }
    }
}

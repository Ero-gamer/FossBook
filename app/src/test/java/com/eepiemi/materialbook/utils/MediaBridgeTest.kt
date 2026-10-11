package com.eepiemi.materialbook.utils

import com.eepiemi.materialbook.utils.jsBridge.FACEBOOK_ORIGIN_RULES
import com.eepiemi.materialbook.utils.jsBridge.MediaRequest
import com.eepiemi.materialbook.utils.jsBridge.isBlockedDownloadType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The page scripts' messages to AstryxMedia, and what DownloadBridge refuses to save. */
class MediaBridgeTest {

    private val dataUrl = "data:image/png;base64,iVBORw0KGgo="

    @Test
    fun parsesDownloadAndCopy() {
        assertEquals(
            MediaRequest(MediaRequest.DOWNLOAD, "image/png", dataUrl),
            MediaRequest.parse("download\nimage/png\n$dataUrl")
        )
        assertEquals(
            MediaRequest(MediaRequest.COPY, "image/jpeg", dataUrl),
            MediaRequest.parse("copy\nimage/jpeg\n$dataUrl")
        )
    }

    @Test
    fun emptyMimeType_isKept() {
        // scripts.js passes blob.type, which can be empty.
        assertEquals(MediaRequest(MediaRequest.DOWNLOAD, "", dataUrl), MediaRequest.parse("download\n\n$dataUrl"))
    }

    @Test
    fun rejectsMalformedMessages() {
        assertNull(MediaRequest.parse(null))
        assertNull(MediaRequest.parse(""))
        assertNull(MediaRequest.parse("download"))
        assertNull(MediaRequest.parse("download\nimage/png"))
        assertNull(MediaRequest.parse("delete\nimage/png\n$dataUrl"))
        assertNull(MediaRequest.parse("DOWNLOAD\nimage/png\n$dataUrl"))
    }

    @Test
    fun apkPackages_areNotSaved() {
        assertTrue(isBlockedDownloadType("application/vnd.android.package-archive"))
        assertTrue(isBlockedDownloadType("Application/Vnd.Android.Package-Archive"))
        assertTrue(isBlockedDownloadType(" application/vnd.android.package-archive; charset=binary"))
    }

    @Test
    fun mediaAndDocuments_areSaved() {
        listOf("image/png", "image/jpeg", "video/mp4", "application/pdf", "application/zip", "", "text/plain")
            .forEach { assertFalse(it, isBlockedDownloadType(it)) }
    }

    @Test
    fun originRules_coverFacebookAndMessengerOnly() {
        assertEquals(
            setOf("https://facebook.com", "https://*.facebook.com", "https://messenger.com", "https://*.messenger.com"),
            FACEBOOK_ORIGIN_RULES
        )
    }
}

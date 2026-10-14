package com.eepiemi.materialbook.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShareMediaTest {

    @After
    fun tearDown() = ShareMediaQueue.clear()

    private fun photo(name: String = "a.jpg", size: Long = 1000) = ShareCandidate(name, "image/jpeg", size)
    private fun video(name: String = "v.mp4", size: Long = 1000) = ShareCandidate(name, "video/mp4", size)

    @Test
    fun theTarget_comesFromTheShareEntryUsed() {
        assertEquals(ShareTarget.FEED, ShareMedia.targetOf("com.eepiemi.materialbook.ShareToFeed"))
        assertEquals(ShareTarget.STORY, ShareMedia.targetOf("com.eepiemi.materialbook.ShareToStory"))
        assertNull(ShareMedia.targetOf("com.eepiemi.materialbook.MainActivity"))
        assertNull(ShareMedia.targetOf(null))
    }

    @Test
    fun theFeed_takesPhotosOnly_storiesTakeVideosToo() {
        val shared = listOf(photo("1.jpg"), video(), photo("2.png").copy(mimeType = "image/png"))
        assertEquals(listOf("1.jpg", "2.png"), ShareMedia.select(ShareTarget.FEED, shared).accepted.map { it.name })
        assertEquals(1, ShareMedia.select(ShareTarget.FEED, shared).skipped)
        assertEquals(3, ShareMedia.select(ShareTarget.STORY, shared).accepted.size)
        assertFalse(ShareMedia.fits(ShareTarget.STORY, "application/pdf", 100))
    }

    @Test
    fun tooBigOrTooMany_areLeftOut_andCounted() {
        assertFalse(ShareMedia.fits(ShareTarget.FEED, "image/jpeg", ShareMedia.MAX_PHOTO_BYTES + 1))
        assertFalse(ShareMedia.fits(ShareTarget.STORY, "video/mp4", ShareMedia.MAX_VIDEO_BYTES + 1))
        assertFalse(ShareMedia.fits(ShareTarget.FEED, "image/jpeg", 0))
        // An unknown size passes: the copy enforces the limit.
        assertTrue(ShareMedia.fits(ShareTarget.FEED, "image/jpeg", -1))

        val many = List(ShareMedia.MAX_STORY_ITEMS + 3) { photo("$it.jpg") }
        val selection = ShareMedia.select(ShareTarget.STORY, many)
        assertEquals(ShareMedia.MAX_STORY_ITEMS, selection.accepted.size)
        assertEquals(3, selection.skipped)
    }

    @Test
    fun safeNames_keepOnlySimpleCharacters_andAreNumbered() {
        assertEquals("0_IMG_2024_01.jpg", ShareMedia.safeName("IMG_2024 01.jpg", 0, "image/jpeg"))
        assertEquals("1_name.png", ShareMedia.safeName("../../evil/name.png", 1, "image/png"))
        assertEquals("2_shared.mp4", ShareMedia.safeName("סרטון", 2, "video/mp4"))
        assertEquals("3_shared.jpeg", ShareMedia.safeName("", 3, "image/jpeg"))
    }

    @Test
    fun theQueue_handsOutFilesOneByOne_orAllAtOnce_forItsTargetOnly() {
        val files = listOf("a", "b", "c").map { SharedFile(File(it), it, "image/jpeg") }
        ShareMediaQueue.start(ShareTarget.STORY, files)
        assertFalse(ShareMediaQueue.pending(ShareTarget.FEED))
        assertNull(ShareMediaQueue.take(ShareTarget.FEED))
        assertEquals("a", ShareMediaQueue.take(ShareTarget.STORY)?.name)
        assertEquals(2, ShareMediaQueue.remaining(ShareTarget.STORY))
        assertEquals(listOf("b", "c"), ShareMediaQueue.takeAll(ShareTarget.STORY).map { it.name })
        assertFalse(ShareMediaQueue.pending(ShareTarget.STORY))
        assertNull(ShareMediaQueue.take(ShareTarget.STORY))
    }

    @Test
    fun aNewShare_replacesTheOneInProgress() {
        ShareMediaQueue.start(ShareTarget.STORY, listOf(SharedFile(File("a"), "a", "image/jpeg")))
        ShareMediaQueue.start(ShareTarget.FEED, listOf(SharedFile(File("b"), "b", "image/jpeg")))
        assertFalse(ShareMediaQueue.pending(ShareTarget.STORY))
        assertEquals("b", ShareMediaQueue.take(ShareTarget.FEED)?.name)
    }

    @Test
    fun theStoryCreator_isRecognized() {
        assertTrue(isStoryCreatePage("https://www.facebook.com/stories/create"))
        assertTrue(isStoryCreatePage("https://www.facebook.com/stories/create/?ref=x"))
        assertFalse(isStoryCreatePage("https://m.facebook.com/stories/create"))
        assertFalse(isStoryCreatePage("https://www.facebook.com/stories/123/"))
        assertFalse(isStoryCreatePage(null))
    }
}

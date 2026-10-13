package com.eepiemi.materialbook.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartPageTest {

    @Test
    fun noLink_opensHome_orFeedsWhenOn() {
        assertEquals(HOME_PAGE_URL, startPageUrl(null, startOnFeeds = false))
        assertEquals(FEEDS_PAGE_URL, startPageUrl(null, startOnFeeds = true))
    }

    @Test
    fun aLink_alwaysWins() {
        val link = "https://m.facebook.com/groups/123/"
        assertEquals(link, startPageUrl(link, startOnFeeds = false))
        assertEquals(link, startPageUrl(link, startOnFeeds = true))
    }

    @Test
    fun bothPages_areFacebookPagesTheAppShows() {
        assertTrue(isFacebookWebUrl(HOME_PAGE_URL))
        assertTrue(isFacebookWebUrl(FEEDS_PAGE_URL))
    }
}

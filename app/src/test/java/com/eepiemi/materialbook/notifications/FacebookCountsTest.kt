package com.eepiemi.materialbook.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FacebookCountsTest {

    // Facebook's tab bar as m.facebook.com sends it (UI in Hebrew; the labels stay English).
    private fun tab(label: String, selected: Boolean = false) =
        "<div role=\"tab\" tabindex=\"0\" aria-label=\"‎$label‎\" aria-selected=\"$selected\" data-focusable=\"true\">"

    private val tabBar = tab("feed, 895 new, 1 of 5") + tab("friends, 2 new, 2 of 5") +
        tab("messages, 3 of 5") + tab("notifications, 99+ new, 4 of 5", selected = true) +
        tab("marketplace, 1 new, 5 of 5") + "<span>מה בא לך לפרסם?</span>"

    @Test
    fun parse_readsTheCountersFromTheTabBar() {
        assertEquals(
            mapOf(
                FacebookCounter.NOTIFICATIONS to UnreadCount(99, capped = true),
                FacebookCounter.MESSAGES to UnreadCount(0),
                FacebookCounter.FRIEND_REQUESTS to UnreadCount(2),
            ),
            FacebookCounts.parse(tabBar)
        )
    }

    @Test
    fun parse_withoutATabBar_knowsNothing() {
        assertNull(FacebookCounts.parse("<html><form id=\"login_form\"></form></html>"))
    }

    @Test
    fun parseLabels_readsTheLabelsTheOpenPageLists() {
        val labels = listOf("‎feed, 1 of 5‎", "‎friends, 2 of 5‎",
            "‎messages, 1 new, 3 of 5‎", "‎notifications, 4 of 5‎", "Close")
        assertEquals(
            mapOf(
                FacebookCounter.NOTIFICATIONS to UnreadCount(0),
                FacebookCounter.MESSAGES to UnreadCount(1),
                FacebookCounter.FRIEND_REQUESTS to UnreadCount(0),
            ),
            FacebookCounts.parseLabels(labels)
        )
        assertNull(FacebookCounts.parseLabels(listOf("Close", "Menu")))
    }

    @Test
    fun moreUnread_thanLastTime_notifies() {
        assertEquals(CountAction.Notify(UnreadCount(3)), FacebookCounts.action(UnreadCount(1), UnreadCount(3)))
        assertEquals(CountAction.Notify(UnreadCount(2)), FacebookCounts.action(null, UnreadCount(2)))
        assertEquals(
            CountAction.Notify(UnreadCount(99, true)),
            FacebookCounts.action(UnreadCount(99), UnreadCount(99, true))
        )
    }

    @Test
    fun theSameCount_doesNothing() {
        assertEquals(CountAction.Nothing, FacebookCounts.action(UnreadCount(3), UnreadCount(3)))
        assertEquals(CountAction.Nothing, FacebookCounts.action(null, UnreadCount(0)))
        assertEquals(CountAction.Nothing, FacebookCounts.action(UnreadCount(0), UnreadCount(0)))
    }

    @Test
    fun someRead_updatesQuietly_allRead_clears() {
        assertEquals(CountAction.Update(UnreadCount(1)), FacebookCounts.action(UnreadCount(3), UnreadCount(1)))
        assertEquals(CountAction.Clear, FacebookCounts.action(UnreadCount(3), UnreadCount(0)))
    }

    @Test
    fun aCappedCount_isShownWithItsPlus() {
        assertEquals("99+", UnreadCount(99, capped = true).label)
        assertEquals("4", UnreadCount(4).label)
    }
}

package com.eepiemi.materialbook.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Android notifications FacebookCountsWorker posts for the counts a check found (the
 * fetching and parsing are covered by FacebookCountsTest): new unread posts one, fewer updates
 * it, none clears it.
 */
@RunWith(AndroidJUnit4::class)
class FacebookCountsNotificationTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun counts(notifications: Int = 0, messages: Int = 0, friends: Int = 0) = mapOf(
        FacebookCounter.NOTIFICATIONS to UnreadCount(notifications),
        FacebookCounter.MESSAGES to UnreadCount(messages),
        FacebookCounter.FRIEND_REQUESTS to UnreadCount(friends),
    )

    private fun shown(counter: FacebookCounter): Notification? =
        manager.activeNotifications.firstOrNull { it.id == counter.notificationId }?.notification

    private fun title(counter: FacebookCounter) =
        shown(counter)?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        FacebookCountsWorker.schedule(context, enabled = false)
    }

    @After
    fun tearDown() = FacebookCountsWorker.schedule(context, enabled = false)

    @Test
    fun newUnread_isNotified_withItsCount() {
        FacebookCountsWorker.applyCounts(context, counts(notifications = 3, friends = 1), notify = true)
        assertEquals(3, shown(FacebookCounter.NOTIFICATIONS)?.number)
        assertEquals(1, shown(FacebookCounter.FRIEND_REQUESTS)?.number)
        assertNull(shown(FacebookCounter.MESSAGES))
    }

    @Test
    fun fewerUnread_updatesIt_noneClearsIt() {
        FacebookCountsWorker.applyCounts(context, counts(notifications = 3), notify = true)
        val first = title(FacebookCounter.NOTIFICATIONS)
        FacebookCountsWorker.applyCounts(context, counts(notifications = 1), notify = true)
        assertEquals(1, shown(FacebookCounter.NOTIFICATIONS)?.number)
        assertNotEquals(first, title(FacebookCounter.NOTIFICATIONS))
        FacebookCountsWorker.applyCounts(context, counts(notifications = 0), notify = true)
        assertNull(shown(FacebookCounter.NOTIFICATIONS))
    }

    @Test
    fun countsRecordedWithoutNotifying_areTheBaseline() {
        // What the user left the app with: not notified, and the same count later isn't either.
        FacebookCountsWorker.applyCounts(context, counts(messages = 2), notify = false)
        assertNull(shown(FacebookCounter.MESSAGES))
        FacebookCountsWorker.applyCounts(context, counts(messages = 2), notify = true)
        assertNull(shown(FacebookCounter.MESSAGES))
        FacebookCountsWorker.applyCounts(context, counts(messages = 4), notify = true)
        assertEquals(4, shown(FacebookCounter.MESSAGES)?.number)
    }

    @Test
    fun aNotificationTap_opensItsTab() {
        FacebookCountsWorker.applyCounts(context, counts(notifications = 2), notify = true)
        val intent = shown(FacebookCounter.NOTIFICATIONS)?.contentIntent
        assertNotNull(intent)
        assertEquals(context.packageName, intent!!.creatorPackage)
    }
}

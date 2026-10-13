package com.eepiemi.materialbook.notifications

/**
 * The unread counters on m.facebook.com's tab bar that "Facebook notifications" (Settings) turns
 * into Android notifications, with the page a tap opens. Facebook labels its tabs in English in
 * every language ("notifications, 3 new, 4 of 5"), in the HTML its server sends, so the counts
 * can be read without a WebView.
 */
enum class FacebookCounter(val tab: String, val url: String, val notificationId: Int) {
    NOTIFICATIONS("notifications", "https://m.facebook.com/notifications/", 2001),
    MESSAGES("messages", "https://m.facebook.com/messages/", 2002),
    FRIEND_REQUESTS("friends", "https://m.facebook.com/friends/", 2003),
}

/** An unread count; [capped] when Facebook shows it as "99+". */
data class UnreadCount(val count: Int, val capped: Boolean = false) {
    val label: String get() = if (capped) "$count+" else count.toString()
}

/** What to do with one counter's Android notification after a check. */
sealed interface CountAction {
    /** Post (or replace) it with an alert: there's more unread than last time. */
    data class Notify(val unread: UnreadCount) : CountAction
    /** Some were read elsewhere but not all: update it quietly if it's showing. */
    data class Update(val unread: UnreadCount) : CountAction
    /** All read: remove it. */
    data object Clear : CountAction
    data object Nothing : CountAction
}

object FacebookCounts {

    // A tab's aria-label: "‎notifications, 3 new, 4 of 5‎", with Left-to-Right Marks around it.
    // A tab without unread items has no "N new".
    private const val LABEL = """[‎‏]?([a-z]+), (?:(\d+)(\+)? new, )?\d+ of \d+[‎‏]?"""
    private val TAB_LABEL_IN_HTML = Regex("""aria-label="$LABEL"""")
    private val TAB_LABEL = Regex("^$LABEL$")

    /** JavaScript that lists the tab bar's labels on the page that's open, as a JSON array. */
    const val TAB_LABELS_JS =
        "Array.prototype.map.call(document.querySelectorAll('[role=\"tab\"][aria-label]'), " +
            "function (tab) { return tab.getAttribute('aria-label'); })"

    /**
     * The counts on the tab bar of [html] (an m.facebook.com page), or null if it has no tab bar
     * (logged out: the login page; an error page), so nothing is known.
     */
    fun parse(html: String): Map<FacebookCounter, UnreadCount>? = counts(TAB_LABEL_IN_HTML.findAll(html))

    /** The counts in the tab bar's [labels] (from [TAB_LABELS_JS]), or null if there's none. */
    fun parseLabels(labels: List<String>): Map<FacebookCounter, UnreadCount>? =
        counts(labels.mapNotNull { TAB_LABEL.find(it.trim()) }.asSequence())

    private fun counts(matches: Sequence<MatchResult>): Map<FacebookCounter, UnreadCount>? {
        val tabs = matches.associate { match ->
            val (tab, count, plus) = match.destructured
            tab to UnreadCount(count.toIntOrNull() ?: 0, plus.isNotEmpty())
        }
        if (FacebookCounter.entries.none { it.tab in tabs }) return null
        return FacebookCounter.entries.associateWith { tabs[it.tab] ?: UnreadCount(0) }
    }

    /** What a check that found [now], where the last one found [before], means for the user. */
    fun action(before: UnreadCount?, now: UnreadCount): CountAction = when {
        now.count == 0 -> if ((before?.count ?: 0) > 0) CountAction.Clear else CountAction.Nothing
        before == null || now.count > before.count || (now.capped && !before.capped) ->
            CountAction.Notify(now)
        now == before -> CountAction.Nothing
        else -> CountAction.Update(now)
    }
}

package com.eepiemi.materialbook.utils

/** Facebook's Home feed, in the order Facebook picks. */
const val HOME_PAGE_URL = "https://facebook.com/"

/** Facebook's Feeds page: posts from friends, groups and Pages, most recent first. */
const val FEEDS_PAGE_URL = "https://facebook.com/?filter=all&sk=h_chr"

/**
 * The page the app opens on: the link it was opened with, if any, else Feeds with "Start on
 * Feeds" (Settings) on, else Home.
 */
fun startPageUrl(linkUrl: String?, startOnFeeds: Boolean): String =
    linkUrl ?: if (startOnFeeds) FEEDS_PAGE_URL else HOME_PAGE_URL

package com.eepiemi.materialbook.utils

import java.net.URLDecoder

/**
 * The web fallback of an Android `intent://` link (`S.browser_fallback_url`), if it has an
 * http(s) one. Facebook answers full loads of some pages (profiles, for one) with a redirect
 * to `intent://…;package=com.facebook.katana;S.browser_fallback_url=…`, and the fallback is
 * the same page on the web (marked `from_intent_redirect=1`, so it doesn't redirect again).
 */
fun intentFallbackUrl(url: String): String? {
    if (!url.startsWith("intent:", ignoreCase = true)) return null
    val raw = url.substringAfter("#Intent;", "")
        .split(';')
        .firstOrNull { it.startsWith("S.browser_fallback_url=") }
        ?.substringAfter('=')
        ?: return null
    val fallback = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull() ?: return null
    return fallback.takeIf { it.startsWith("https://") || it.startsWith("http://") }
}

private val FACEBOOK_WEB_URL =
    Regex("""^https?://(?!(?:l|lm)\.)(?:[^/?#:]+\.)?(?:facebook|messenger)\.com(?:[/?#:]|$)""", RegexOption.IGNORE_CASE)

/**
 * A Facebook/Messenger page the app shows itself (the host is facebook.com, messenger.com or
 * a subdomain); outbound l./lm.facebook.com redirects and everything else go to the system.
 */
fun isFacebookWebUrl(url: String): Boolean = FACEBOOK_WEB_URL.containsMatchIn(url)

// Schemes that point at data on this device or run code in the page: handed to ACTION_VIEW
// they would open (and grant access to) local content instead of a web page or another
// app's public link. intent: is handled by intentFallbackUrl.
private val BLOCKED_EXTERNAL_SCHEMES = setOf(
    "file", "content", "javascript", "data", "blob", "about", "android-app", "intent",
    "chrome", "chrome-extension", "filesystem", "view-source",
)
private val URL_SCHEME = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*):""")

/**
 * Whether a link the page navigates to may be handed to another app (the browser, the
 * dialer, a store or messaging app). The intent also carries CATEGORY_BROWSABLE, so only
 * apps that accept links from web pages can receive it.
 */
fun isLaunchableExternalUrl(url: String): Boolean {
    val scheme = URL_SCHEME.find(url.trim())?.groupValues?.get(1)?.lowercase() ?: return false
    return scheme !in BLOCKED_EXTERNAL_SCHEMES
}

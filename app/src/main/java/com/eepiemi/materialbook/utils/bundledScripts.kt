package com.eepiemi.materialbook.utils

import androidx.annotation.RawRes

// Page scripts always come from the copy bundled in the (signed) APK. They used
// to be fetched from main on GitHub at every launch, which let anyone who could
// push to main run code inside every user's logged-in Facebook page.
data class Script(
    val isEnabled: Boolean,
    @param:RawRes val resourceId: Int
)

// Concatenates the enabled scripts in the caller's order.
fun bundledScripts(
    scripts: List<Script>,
    readBundled: (Int) -> String
): String = buildString {
    scripts.filter { it.isEnabled }.forEach { append(readBundled(it.resourceId)) }
}

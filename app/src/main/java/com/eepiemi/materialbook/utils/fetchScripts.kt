package com.eepiemi.materialbook.utils

import androidx.annotation.RawRes
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest


const val SCRIPT_SRC = "https://raw.githubusercontent.com/ofirc73/AstryxBook/refs/heads/main/app/src/main/res/raw/"

// Fetches were previously unbounded (no timeout at all - success or
// exception, whichever came first, however long that took) and strictly
// sequential (one script's GET awaited before the next started). On a cold
// network - e.g. right after an app cache clear forces every fetch back onto
// the network instead of reusing a cached response - this could leave
// userScripts (including pip_video_detector.js, the PiP active-video
// tracker) not finished loading for several seconds, during which PiP focus
// mode has no reliable data to work with. Bounding each fetch and running
// them concurrently turns that into a small, predictable worst case: falling
// straight back to the bundled resource, same as any other fetch failure
// already did.
internal const val FETCH_TIMEOUT_MS = 1000L

// A fetched script runs inside the logged-in Facebook page with every native
// bridge, so anyone who could push to main would otherwise get code execution
// in every installed app on its next launch. A fetched script is only used
// when its SHA-256 matches the copy bundled in the (signed) APK; anything else
// is rejected in favour of that bundled copy. Line endings are normalised
// first, so a Windows checkout with CRLF files still matches GitHub's LF copy.
internal fun scriptSha256(content: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(content.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

internal fun isTrustedScript(fetched: String, bundled: String): Boolean =
    scriptSha256(fetched) == scriptSha256(bundled)

data class Script(
    val isEnabled: Boolean,
    @param:RawRes val resourceId:  Int,
    val scriptTitle: String
)

suspend fun fetchScripts(
    scripts: List<Script>,
    fallbackContent: (Int) -> String,
    httpClient: HttpClient = HttpClient(OkHttp)
): String = coroutineScope {
    val deferredContents = scripts.filter { it.isEnabled }.map { script ->
        async {
            val fetched = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                runCatching {
                    val res = httpClient.get(SCRIPT_SRC + script.scriptTitle)
                    if (res.status == HttpStatusCode.OK) {
                        res.body() as String
                    } else {
                        throw Exception()
                    }
                }.getOrNull()
            }
            val bundled = fallbackContent(script.resourceId)
            if (fetched != null && isTrustedScript(fetched, bundled)) fetched else bundled
        }
    }
    // async preserves list order regardless of completion order, so the
    // concatenated script content still evaluates in the same order the
    // caller specified.
    buildString {
        deferredContents.forEach { append(it.await()) }
    }
}

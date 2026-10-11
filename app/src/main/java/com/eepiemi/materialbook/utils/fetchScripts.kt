package com.eepiemi.materialbook.utils

import androidx.annotation.RawRes
import com.eepiemi.materialbook.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.security.PublicKey


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

data class Script(
    val isEnabled: Boolean,
    @param:RawRes val resourceId:  Int,
    val scriptTitle: String
)

/**
 * The enabled [scripts], concatenated in list order. Each one is fetched from [SCRIPT_SRC]
 * with its signature from [SIGNATURE_SRC] and used only if the signature verifies against
 * [publicKey] (see ScriptSignature.kt); otherwise, and on any network failure, the bundled
 * copy is used. A null [publicKey] (no key built in, or "Use bundled scripts only") makes no
 * network request at all.
 */
suspend fun fetchScripts(
    scripts: List<Script>,
    fallbackContent: (Int) -> String,
    httpClient: HttpClient = HttpClient(OkHttp),
    publicKey: PublicKey? = parseScriptSigningKey(BuildConfig.SCRIPT_SIGNING_PUBLIC_KEY),
): String = coroutineScope {
    val deferredContents = scripts.filter { it.isEnabled }.map { script ->
        async {
            val fetched = publicKey?.let { key ->
                withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                    // Its own scope: a failed fetch fails only this script, not the others.
                    runCatching {
                        coroutineScope {
                            val content = async { fetchText(httpClient, SCRIPT_SRC + script.scriptTitle) }
                            val signature = async { fetchText(httpClient, SIGNATURE_SRC + script.scriptTitle + ".sig") }
                            normalizeScript(content.await()).takeIf {
                                isScriptSignatureValid(script.scriptTitle, it, signature.await(), key)
                            }
                        }
                    }.getOrNull()
                }
            }
            fetched ?: fallbackContent(script.resourceId)
        }
    }
    // async preserves list order regardless of completion order, so the
    // concatenated script content still evaluates in the same order the
    // caller specified.
    buildString {
        deferredContents.forEach { append(it.await()) }
    }
}

private suspend fun fetchText(httpClient: HttpClient, url: String): String {
    val res = httpClient.get(url)
    if (res.status != HttpStatusCode.OK) throw Exception("HTTP ${res.status.value} for $url")
    return res.body<ByteArray>().toString(Charsets.UTF_8)
}

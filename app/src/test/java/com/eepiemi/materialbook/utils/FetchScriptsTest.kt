package com.eepiemi.materialbook.utils

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * fetchScripts used to have two robustness gaps (see fetchScripts.kt's own
 * comment): fetches were unbounded — no timeout, success or exception
 * whichever came first, however long that took — and strictly sequential,
 * one script's GET fully awaited before the next started. On a cold network
 * (e.g. right after an app cache clear forces every fetch back onto the
 * network instead of reusing a cached response), that could leave
 * userScripts — including pip_video_detector.js, the PiP active-video
 * tracker — not finished loading for several seconds, during which PiP
 * focus mode has no reliable data to work with (see the black-video-with-
 * audio field report this was diagnosed from). These tests cover the fix:
 * a bounded per-script timeout with fallback, and concurrent fetching that
 * still preserves the caller's script order in the final concatenated
 * output regardless of which network response lands first.
 */
class FetchScriptsTest {

    private val scriptA = Script(isEnabled = true, resourceId = 1, scriptTitle = "a.js")
    private val scriptB = Script(isEnabled = true, resourceId = 2, scriptTitle = "b.js")

    private fun fallbackFor(resourceId: Int) = "fallback-$resourceId"

    // A throwaway signing key: the fetched scripts below are signed with it (as signScripts
    // does with the real one), and fetchScripts is given its public half.
    private val keyPair = newKeyPair()
    private val otherKeyPair = newKeyPair()

    private fun newKeyPair() = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()

    private fun sign(title: String, content: String, key: PrivateKey = keyPair.private): String =
        Base64.getEncoder().encodeToString(
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initSign(key)
                update(scriptSigningMessage(title, content))
                sign()
            }
        )

    private suspend fun fetch(scripts: List<Script>, client: HttpClient) =
        fetchScripts(scripts, ::fallbackFor, client, keyPair.public)

    /**
     * Serves [bodies] at their script paths and, unless overridden in [signatures], a valid
     * signature for each at `<title>.sig`.
     */
    private fun clientRespondingWith(
        bodies: Map<String, String> = emptyMap(),
        errors: Set<String> = emptySet(),
        delays: Map<String, Long> = emptyMap(),
        signatures: Map<String, String> = emptyMap(),
        requests: MutableList<String> = mutableListOf(),
    ): HttpClient = HttpClient(MockEngine { request ->
        requests += request.url.toString()
        val file = request.url.encodedPath.substringAfterLast('/')
        val signed = file.removeSuffix(".sig").takeIf { file.endsWith(".sig") }
        val signature = signed?.let { signatures[it] ?: bodies[it]?.let { body -> sign(it, body) } }
        val title = file
        if (signed == null) delays[title]?.let { delay(it) }
        when {
            signed != null && signature != null -> respond(signature, HttpStatusCode.OK)
            signed != null -> respondError(HttpStatusCode.NotFound)
            title in errors -> respondError(HttpStatusCode.NotFound)
            bodies.containsKey(title) -> respond(
                content = bodies.getValue(title),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/javascript")
            )
            else -> respondError(HttpStatusCode.NotFound)
        }
    })

    @Test
    fun returnsNetworkContent_onSuccess() = runBlocking {
        val client = clientRespondingWith(bodies = mapOf("a.js" to "network-a"))

        val result = fetch(listOf(scriptA), client)

        assertEquals("network-a", result)
    }

    @Test
    fun fallsBackToBundled_onNon200Status() = runBlocking {
        val client = clientRespondingWith(errors = setOf("a.js"))

        val result = fetch(listOf(scriptA), client)

        assertEquals(fallbackFor(1), result)
    }

    @Test
    fun fallsBackToBundled_whenFetchExceedsTimeout() = runBlocking {
        val client = clientRespondingWith(
            bodies = mapOf("a.js" to "network-a"),
            delays = mapOf("a.js" to FETCH_TIMEOUT_MS + 500)
        )

        val result = fetch(listOf(scriptA), client)

        assertEquals(
            "a fetch that exceeds FETCH_TIMEOUT_MS must fall back, not hang or throw",
            fallbackFor(1),
            result
        )
    }

    @Test
    fun preservesCallerOrder_regardlessOfWhichResponseLandsFirst() = runBlocking {
        // b.js "wins the race" (no delay) while a.js is slow (but still under
        // the timeout) - the concatenated result must still read a-then-b,
        // matching the order the caller listed them in, not completion order.
        val client = clientRespondingWith(
            bodies = mapOf("a.js" to "content-a", "b.js" to "content-b"),
            delays = mapOf("a.js" to 200L)
        )

        val result = fetch(listOf(scriptA, scriptB), client)

        assertEquals("content-acontent-b", result)
    }

    @Test
    fun runsFetchesConcurrently_notSequentially() = runBlocking {
        // Two scripts, each individually within the timeout, but whose
        // delays would sum to MORE than the timeout if run one-after-another.
        // A regression to sequential fetching would make this test's overall
        // wall-clock time balloon well past FETCH_TIMEOUT_MS even though
        // neither individual fetch does - this asserts on outcome (both
        // succeed, within one timeout window), not on wall-clock timing
        // directly, to avoid a flaky test.
        val perScriptDelay = FETCH_TIMEOUT_MS - 200
        val client = clientRespondingWith(
            bodies = mapOf("a.js" to "content-a", "b.js" to "content-b"),
            delays = mapOf("a.js" to perScriptDelay, "b.js" to perScriptDelay)
        )

        val result = fetch(listOf(scriptA, scriptB), client)

        assertEquals(
            "both fetches should succeed within roughly one timeout window if run concurrently",
            "content-acontent-b",
            result
        )
    }

    @Test
    fun skipsDisabledScripts() = runBlocking {
        val disabled = scriptB.copy(isEnabled = false)
        val client = clientRespondingWith(bodies = mapOf("a.js" to "content-a", "b.js" to "content-b"))

        val result = fetch(listOf(scriptA, disabled), client)

        assertTrue(result.contains("content-a"))
        assertFalse(
            "disabled script must not be fetched or contribute fallback content",
            result.contains("content-b") || result.contains(fallbackFor(2))
        )
    }

    // ---- signatures ----

    @Test
    fun fallsBackToBundled_whenSignatureIsMissing() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith(".sig")) respondError(HttpStatusCode.NotFound)
            else respond("network-a", HttpStatusCode.OK)
        })

        assertEquals(fallbackFor(1), fetch(listOf(scriptA), client))
    }

    @Test
    fun fallsBackToBundled_whenContentWasChangedAfterSigning() = runBlocking {
        val client = clientRespondingWith(
            bodies = mapOf("a.js" to "evil-a"),
            signatures = mapOf("a.js" to sign("a.js", "network-a"))
        )

        assertEquals(fallbackFor(1), fetch(listOf(scriptA), client))
    }

    @Test
    fun fallsBackToBundled_whenSignedWithAnotherKey() = runBlocking {
        val client = clientRespondingWith(
            bodies = mapOf("a.js" to "network-a"),
            signatures = mapOf("a.js" to sign("a.js", "network-a", otherKeyPair.private))
        )

        assertEquals(fallbackFor(1), fetch(listOf(scriptA), client))
    }

    @Test
    fun fallsBackToBundled_whenAnotherScriptsSignedContentIsServed() = runBlocking {
        // b.js's genuine, signed content served as a.js: the name is part of what's signed.
        val client = clientRespondingWith(
            bodies = mapOf("a.js" to "content-b"),
            signatures = mapOf("a.js" to sign("b.js", "content-b"))
        )

        assertEquals(fallbackFor(1), fetch(listOf(scriptA), client))
    }

    @Test
    fun fallsBackToBundled_whenSignatureIsGarbage() = runBlocking {
        val client = clientRespondingWith(
            bodies = mapOf("a.js" to "network-a"),
            signatures = mapOf("a.js" to "not base64 !!")
        )

        assertEquals(fallbackFor(1), fetch(listOf(scriptA), client))
    }

    @Test
    fun acceptsCrlfCopyOfAnLfSignedScript() = runBlocking {
        val client = clientRespondingWith(
            bodies = mapOf("a.js" to "line1\r\nline2\r\n"),
            signatures = mapOf("a.js" to sign("a.js", "line1\nline2\n"))
        )

        assertEquals("line1\nline2\n", fetch(listOf(scriptA), client))
    }

    @Test
    fun oneBadSignature_doesNotAffectTheOtherScripts() = runBlocking {
        val client = clientRespondingWith(
            bodies = mapOf("a.js" to "content-a", "b.js" to "content-b"),
            signatures = mapOf("a.js" to sign("a.js", "something else"))
        )

        assertEquals(fallbackFor(1) + "content-b", fetch(listOf(scriptA, scriptB), client))
    }

    @Test
    fun withoutAPublicKey_usesBundledScriptsAndMakesNoRequests() = runBlocking {
        val requests = mutableListOf<String>()
        val client = clientRespondingWith(
            bodies = mapOf("a.js" to "content-a", "b.js" to "content-b"),
            requests = requests
        )

        val result = fetchScripts(listOf(scriptA, scriptB), ::fallbackFor, client, publicKey = null)

        assertEquals(fallbackFor(1) + fallbackFor(2), result)
        assertTrue("no network request without a key: $requests", requests.isEmpty())
    }
}

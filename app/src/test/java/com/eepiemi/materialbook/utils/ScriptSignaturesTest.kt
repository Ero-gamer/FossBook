package com.eepiemi.materialbook.utils

import com.eepiemi.materialbook.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Every page script in res/raw has a current signature in src/main/script-signatures,
 * made with the committed key (`./gradlew signScripts -PscriptSigningKey=<private key>`).
 * Installed apps fetch both from main and only run a script whose signature verifies, so a
 * script pushed without re-signing would silently fall back to each app's bundled copy.
 *
 * Until a key is committed (app/script-signing-key.pub) the app never fetches scripts, and
 * there is nothing to check.
 */
class ScriptSignaturesTest {

    private val appDir: File by lazy {
        listOf(File("."), File("app")).firstOrNull { File(it, "src/main/res/raw").isDirectory }
            ?: error("Could not locate the app module from ${File(".").absolutePath}")
    }

    private val publicKeyText: String? by lazy {
        File(appDir, "script-signing-key.pub").takeIf { it.isFile }?.readText()?.trim()
    }

    @Test
    fun buildConfigCarriesTheCommittedKey() {
        assertEquals(publicKeyText.orEmpty(), BuildConfig.SCRIPT_SIGNING_PUBLIC_KEY)
    }

    @Test
    fun everyScriptHasAValidSignature() {
        assumeTrue("No script signing key committed yet", !publicKeyText.isNullOrEmpty())
        val publicKey = parseScriptSigningKey(publicKeyText)
        assertNotNull("script-signing-key.pub is not an EC public key", publicKey)

        val scripts = File(appDir, "src/main/res/raw").listFiles { f -> f.name.endsWith(".js") }.orEmpty()
        assertTrue(scripts.isNotEmpty())
        val problems = scripts.sortedBy { it.name }.mapNotNull { script ->
            val signatureFile = File(appDir, "src/main/script-signatures/${script.name}.sig")
            when {
                !signatureFile.isFile -> "${script.name}: no signature"
                !isScriptSignatureValid(script.name, script.readText(), signatureFile.readText(), publicKey!!) ->
                    "${script.name}: signature doesn't match (re-run signScripts)"
                else -> null
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun signatureSourceMatchesScriptSource() {
        // The signatures are served from the same repository and branch as the scripts.
        assertEquals(
            SCRIPT_SRC.substringBefore("/app/src/main/"),
            SIGNATURE_SRC.substringBefore("/app/src/main/")
        )
        assertTrue(SIGNATURE_SRC.endsWith("/app/src/main/script-signatures/"))
    }
}

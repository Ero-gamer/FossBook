package com.eepiemi.materialbook.utils

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Page scripts fetched from GitHub run inside the logged-in Facebook session, so each one
 * must carry a signature made with the maintainer's offline key (ECDSA P-256, SHA-256;
 * native on every supported Android version). A script whose signature is missing or
 * doesn't verify is not used: [fetchScripts] falls back to the bundled copy.
 *
 * The signature covers the script's name as well as its content, so a signed script can't
 * be served in place of another one. Line endings are normalized to LF on both sides: a
 * Windows checkout has CRLF, GitHub serves the LF the repository stores.
 *
 * Keys and signatures are made by the `generateScriptSigningKey` and `signScripts` Gradle
 * tasks (see app/build.gradle.kts); the shared format lives in [scriptSigningMessage].
 */
const val SIGNATURE_SRC =
    "https://raw.githubusercontent.com/ofirc73/AstryxBook/refs/heads/main/app/src/main/script-signatures/"

const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

private const val MESSAGE_PREFIX = "astryxbook-script-v1\n"

fun normalizeScript(content: String): String = content.replace("\r\n", "\n")

/** The exact bytes a script's signature covers. */
fun scriptSigningMessage(scriptTitle: String, content: String): ByteArray =
    (MESSAGE_PREFIX + scriptTitle + "\n" + normalizeScript(content)).toByteArray(Charsets.UTF_8)

/** A base64 X.509 (SubjectPublicKeyInfo) EC public key, or null if it isn't one. */
fun parseScriptSigningKey(base64: String?): PublicKey? {
    if (base64.isNullOrBlank()) return null
    return runCatching {
        KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64.trim())))
    }.getOrNull()
}

fun isScriptSignatureValid(
    scriptTitle: String,
    content: String,
    signatureBase64: String,
    publicKey: PublicKey,
): Boolean = runCatching {
    val signature = Base64.getDecoder().decode(signatureBase64.trim())
    Signature.getInstance(SIGNATURE_ALGORITHM).run {
        initVerify(publicKey)
        update(scriptSigningMessage(scriptTitle, content))
        verify(signature)
    }
}.getOrDefault(false)

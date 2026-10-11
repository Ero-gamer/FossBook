import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("jacoco")
}

configure<JacocoPluginExtension> {
    toolVersion = "0.8.12"
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

// ---- Script signing ----
// Page scripts fetched from GitHub at runtime must be signed (see ScriptSignature.kt). The
// private key stays with the maintainer, outside the repository; only the public key and
// the signatures are committed.
//   One-time:  ./gradlew generateScriptSigningKey -PscriptSigningKey=<path outside the repo, e.g. ~/keys/astryxbook-scripts.pk8>
//   After every change to res/raw/*.js:  ./gradlew signScripts -PscriptSigningKey=<same path>
// ScriptSignaturesTest fails while a script is unsigned or its signature is stale.
val SCRIPT_SIGNING_PUBLIC_KEY_FILE = "script-signing-key.pub"
val SCRIPT_SIGNATURES_DIR = "src/main/script-signatures"
val SCRIPT_SIGNING_MESSAGE_PREFIX = "astryxbook-script-v1\n" // same as ScriptSignature.kt

tasks.register("generateScriptSigningKey") {
    group = "script signing"
    description = "Creates the page-script signing key pair (-PscriptSigningKey=<private key file>)."
    val privateKeyPath = providers.gradleProperty("scriptSigningKey")
    val publicKeyFile = layout.projectDirectory.file(SCRIPT_SIGNING_PUBLIC_KEY_FILE).asFile
    val repoDir = rootProject.layout.projectDirectory.asFile
    doLast {
        val privateKeyFile = File(privateKeyPath.orNull ?: error("Pass -PscriptSigningKey=<private key file>"))
        check(!privateKeyFile.exists()) { "$privateKeyFile already exists; not overwriting it" }
        check(!privateKeyFile.canonicalPath.startsWith(repoDir.canonicalPath + File.separator)) {
            "Keep the private key outside the repository (got $privateKeyFile)"
        }
        val generator = java.security.KeyPairGenerator.getInstance("EC")
        generator.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val encoder = java.util.Base64.getEncoder()
        privateKeyFile.absoluteFile.parentFile.mkdirs()
        privateKeyFile.writeText(encoder.encodeToString(pair.private.encoded) + "\n")
        publicKeyFile.writeText(encoder.encodeToString(pair.public.encoded) + "\n")
        println("Private key: $privateKeyFile (keep it out of the repository and back it up)")
        println("Public key:  $publicKeyFile (commit it)")
    }
}

tasks.register("signScripts") {
    group = "script signing"
    description = "Signs every res/raw/*.js page script (-PscriptSigningKey=<private key file>)."
    val privateKeyPath = providers.gradleProperty("scriptSigningKey")
    val rawDir = layout.projectDirectory.dir("src/main/res/raw").asFile
    val signaturesDir = layout.projectDirectory.dir(SCRIPT_SIGNATURES_DIR).asFile
    val publicKeyFile = layout.projectDirectory.file(SCRIPT_SIGNING_PUBLIC_KEY_FILE).asFile
    val messagePrefix = SCRIPT_SIGNING_MESSAGE_PREFIX
    doLast {
        val privateKeyFile = File(privateKeyPath.orNull ?: error("Pass -PscriptSigningKey=<private key file>"))
        val decoder = java.util.Base64.getDecoder()
        val keyFactory = java.security.KeyFactory.getInstance("EC")
        val privateKey = keyFactory.generatePrivate(
            java.security.spec.PKCS8EncodedKeySpec(decoder.decode(privateKeyFile.readText().trim()))
        )
        val publicKey = keyFactory.generatePublic(
            java.security.spec.X509EncodedKeySpec(decoder.decode(publicKeyFile.readText().trim()))
        )
        signaturesDir.mkdirs()
        signaturesDir.listFiles { f -> f.name.endsWith(".sig") }?.forEach { it.delete() }
        val scripts = rawDir.listFiles { f -> f.name.endsWith(".js") }.orEmpty().sortedBy { it.name }
        scripts.forEach { script ->
            val message = (messagePrefix + script.name + "\n" + script.readText().replace("\r\n", "\n"))
                .toByteArray(Charsets.UTF_8)
            val signature = java.security.Signature.getInstance("SHA256withECDSA").run {
                initSign(privateKey)
                update(message)
                sign()
            }
            // Checked against the committed public key, so a mismatched key pair fails here.
            val verified = java.security.Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(message)
                verify(signature)
            }
            check(verified) { "The private key doesn't match $publicKeyFile" }
            File(signaturesDir, script.name + ".sig")
                .writeText(java.util.Base64.getEncoder().encodeToString(signature) + "\n")
        }
        println("Signed ${scripts.size} scripts into $signaturesDir")
    }
}

extensions.configure<ApplicationExtension> {
    namespace = "com.eepiemi.materialbook"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.astryx.book"
        minSdk = 26
        targetSdk = 36

        // CI passes -PversionNameOverride=<tag-without-v> from the resolved
        // release tag (see resolve-version job in create-release.yml) so the
        // APK's actual version metadata matches what's on GitHub Releases,
        // instead of a value that never changes. Falls back to a fixed local
        // dev default when building without that property (plain PR runs,
        // local `./gradlew assembleDebug`, etc).
        val overrideVersion = (project.findProperty("versionNameOverride") as String?)
            ?.takeIf { it.isNotBlank() }
        if (overrideVersion != null) {
            versionName = overrideVersion
            val parts = overrideVersion.split(".").map { it.toIntOrNull() ?: 0 }
            val major = parts.getOrElse(0) { 0 }
            val minor = parts.getOrElse(1) { 0 }
            val patch = parts.getOrElse(2) { 0 }
            // 3-digit budget each for minor/patch before any collision risk
            // (e.g. v1.0.100 and v1.1.0 must never produce the same code).
            versionCode = major * 1_000_000 + minor * 1_000 + patch
        } else {
            versionName = "1.0.0"
            versionCode = 13
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Public half of the page-script signing key (see "Script signing" below). Without
        // it the app never fetches scripts and always runs the bundled copies.
        val scriptSigningPublicKey = providers
            .fileContents(layout.projectDirectory.file(SCRIPT_SIGNING_PUBLIC_KEY_FILE))
            .asText.orNull?.trim().orEmpty()
        buildConfigField("String", "SCRIPT_SIGNING_PUBLIC_KEY", "\"$scriptSigningPublicKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".test"
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true

            // Fix: Tells Gradle to skip stripping native symbols for local debug builds
            packaging.jniLibs.keepDebugSymbols.add("**/*.so")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Existing upstream locale files intentionally use Android's English
        // fallback for older, not-yet-translated keys. Keep the CI lint gate
        // focused on actionable Android issues instead of legacy translation debt.
        disable += "MissingTranslation"
    }
}

dependencies {
    api(libs.compose.webview.multiplatform)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.biometric)
    // BiometricPrompt needs a FragmentActivity; pinned so biometric's old fragment 1.2 isn't used.
    implementation(libs.androidx.fragment.ktx)
    testImplementation(libs.playwright)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.ktor.client.mock)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// AdblockTest drives real browsers against live facebook.com and needs a
// manually-generated login session (see app/src/test/resources/Readme.md) —
// it was never meant to run unattended. Excluded from the default `test`
// task (local and CI alike); run it explicitly with
// `./gradlew test --tests "com.eepiemi.materialbook.AdblockTest"` once
// auth.json is set up.
tasks.withType<Test>().configureEach {
    filter {
        excludeTestsMatching("com.eepiemi.materialbook.AdblockTest")
    }
}

// Combines unit-test + instrumented-test coverage into one report. Both test
// types run as separate CI jobs on separate runners (see ci.yml), so this
// task only *depends on* testDebugUnitTest (regenerates its own .exec fresh,
// cheap, ~30s) — the instrumented .ec file can't be regenerated without a
// device, so ci.yml downloads it as an artifact into place before this runs.
tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest", "bundleDebugClassesToRuntimeJar")
    group = "Reporting"
    description = "Generates a combined Jacoco coverage report (unit + instrumented tests)."

    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    val fileFilter = listOf(
        "**/R.class", "**/R\$*.class", "**/BuildConfig.*", "**/Manifest*.*",
        "**/*Test*.*", "android/**/*.*",
        "**/*\$Lambda\$*.*", "**/*\$inlined\$*.*"
    )

    // Kotlin 2.2's Gradle plugin no longer outputs loose .class files under
    // tmp/kotlin-classes/debug (that path is gone entirely as of this AGP
    // 9.1/Kotlin 2.2/Gradle 9.3.1 combo) — compiled classes are packaged
    // straight into a JAR now. Verified empirically against a local build,
    // not guessed: app/build/intermediates/runtime_app_classes_jar/debug/
    // bundleDebugClassesToRuntimeJar/classes.jar. Re-verify this path if a
    // future toolchain bump breaks the report again — AGP has moved this
    // more than once before.
    classDirectories.setFrom(
        zipTree(layout.buildDirectory.file("intermediates/runtime_app_classes_jar/debug/bundleDebugClassesToRuntimeJar/classes.jar")).matching {
            exclude(fileFilter)
        }
    )
    sourceDirectories.setFrom(files("\$projectDir/src/main/java"))
    executionData.setFrom(
        fileTree(layout.buildDirectory.get().asFile) {
            include(
                "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
                "outputs/unit_test_code_coverage/**/*.exec",
                "jacoco/testDebugUnitTest.exec",
                "outputs/code_coverage/debugAndroidTest/connected/**/*.ec",
                "outputs/code_coverage/**/*.ec"
            )
        }
    )
}

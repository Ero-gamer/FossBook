package com.eepiemi.materialbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The WebView's data directory (`app_webview/`) holds the Facebook session, so it must be
 * left out of Auto Backup (Android 11 and lower), cloud backup and device-to-device
 * transfer (Android 12+). Settings are still backed up: no section may switch to an
 * include list, which would drop them.
 */
class BackupRulesTest {

    private val mainDir: File by lazy {
        listOf(File("src/main"), File("app/src/main")).firstOrNull { it.isDirectory }
            ?: error("Could not locate app src/main from ${File(".").absolutePath}")
    }

    private fun parse(name: String): Element =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File(mainDir, "res/xml/$name"))
            .documentElement

    private fun Element.children(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun assertExcludesWebView(section: Element, label: String) {
        assertTrue(
            "$label must exclude root:app_webview/",
            section.children("exclude").any {
                it.getAttribute("domain") == "root" && it.getAttribute("path") == "app_webview/"
            }
        )
        assertEquals("$label must not use an include list", 0, section.children("include").size)
    }

    @Test
    fun fullBackupContent_excludesWebViewData() {
        assertExcludesWebView(parse("backup_rules.xml"), "full-backup-content")
    }

    @Test
    fun cloudBackup_excludesWebViewData() {
        val sections = parse("data_extraction_rules.xml").children("cloud-backup")
        assertEquals(1, sections.size)
        assertExcludesWebView(sections.single(), "cloud-backup")
    }

    @Test
    fun deviceTransfer_excludesWebViewData() {
        val sections = parse("data_extraction_rules.xml").children("device-transfer")
        assertEquals(1, sections.size)
        assertExcludesWebView(sections.single(), "device-transfer")
    }

    @Test
    fun manifest_usesBothRuleFiles() {
        val manifest = File(mainDir, "AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
    }
}

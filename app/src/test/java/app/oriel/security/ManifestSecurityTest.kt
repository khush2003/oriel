package app.oriel.security

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** The manifest's security settings, read from source so a change to them shows up in review as a failing test. */
class ManifestSecurityTest {
    private val res = File("src/main")
    private val android = "http://schemas.android.com/apk/res/android"

    private fun xml(path: String): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File(res, path)).documentElement

    private fun Element.children(tag: String): List<Element> =
        (0 until getElementsByTagName(tag).length).map { getElementsByTagName(tag).item(it) as Element }

    @Test fun noBackupNoCleartext() {
        val app = xml("AndroidManifest.xml").children("application").single()
        assertEquals("false", app.getAttributeNS(android, "allowBackup"))
        assertEquals("false", app.getAttributeNS(android, "usesCleartextTraffic"))
        assertEquals("@xml/network_security_config", app.getAttributeNS(android, "networkSecurityConfig"))
        assertFalse(app.hasAttributeNS(android, "debuggable"))
    }

    @Test fun onlyTheExpectedComponentsAreExported() {
        val manifest = xml("AndroidManifest.xml")
        val exported = listOf("activity", "service", "receiver", "provider", "activity-alias")
            .flatMap { manifest.children(it) }
            .filter { it.getAttributeNS(android, "exported") == "true" }
            .map { it.getAttributeNS(android, "name") }
            .toSet()
        assertEquals(setOf(".MainActivity", ".playback.PlaybackService", "androidx.media3.session.MediaButtonReceiver"), exported)
    }

    @Test fun onlySystemCertificateAuthorities() {
        val config = xml("res/xml/network_security_config.xml")
        assertTrue(config.children("domain-config").isEmpty())
        val base = config.children("base-config").single()
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
        assertEquals(listOf("system"), base.children("certificates").map { it.getAttribute("src") })
        assertTrue(config.children("debug-overrides").isEmpty())
    }

    @Test fun backupsAndTransfersExcludeEverything() {
        val domains = setOf("root", "file", "database", "sharedpref", "external")
        val extraction = xml("res/xml/data_extraction_rules.xml")
        listOf("cloud-backup", "device-transfer").forEach { section ->
            val rules = extraction.children(section).single()
            assertTrue(section, rules.children("include").isEmpty())
            assertEquals(section, domains, rules.children("exclude").filter { it.getAttribute("path") == "." }.map { it.getAttribute("domain") }.toSet())
        }
        val legacy = xml("res/xml/backup_rules.xml")
        assertTrue(legacy.children("include").isEmpty())
        assertEquals(domains, legacy.children("exclude").filter { it.getAttribute("path") == "." }.map { it.getAttribute("domain") }.toSet())
    }
}

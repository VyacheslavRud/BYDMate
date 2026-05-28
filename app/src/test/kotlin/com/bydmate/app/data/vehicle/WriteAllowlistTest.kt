package com.bydmate.app.data.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class WriteAllowlistTest {

    private val bannedDevs = setOf(1004, 1006, 1007, 1009, 1011, 1012, 1013, 1014, 1016, 1023, 1032)

    // ── Test 5: WriteEntry data class construction with new fields ────────────
    @Test fun `WriteEntry data class construction with new fields works`() {
        val entry = WriteEntry(
            actionName = "test_action",
            dev = 1001,
            writeFid = 12345,
            readbackFid = 67890,
            valueMin = 0,
            valueMax = 100,
            category = "windows",
            validated = true,
            source = "live-leopard3-2026-05-28",
        )
        assertEquals("test_action", entry.actionName)
        assertEquals(1001, entry.dev)
        assertEquals(12345, entry.writeFid)
        assertEquals(67890, entry.readbackFid)
        assertEquals(0, entry.valueMin)
        assertEquals(100, entry.valueMax)
        assertEquals("windows", entry.category)
        assertTrue(entry.validated)
        assertEquals("live-leopard3-2026-05-28", entry.source)
    }

    // ── Test 1: banned dev entries are dropped; safe entries survive ──────────
    @Test fun `no production entry targets a banned dev namespace`() {
        val fixture = """
            {
              "safe_action": { "featureId": 1125122104, "deviceType": 1001, "value": 1 },
              "banned_action": { "featureId": 999999999, "deviceType": 1014, "value": 1 }
            }
        """.trimIndent()
        val allowlist = WriteAllowlist.loadProduction { fixture }

        assertNotNull("safe_action must survive", allowlist.find("safe_action"))
        assertNull("banned_action must be dropped", allowlist.find("banned_action"))

        for (entry in allowlist.allEntries()) {
            if (entry.dev in bannedDevs) {
                fail("WriteAllowlist contains banned dev=${entry.dev} for action=${entry.actionName}")
            }
        }
    }

    // ── Test 2: all 5 required categories present in LIVE_VALIDATED ──────────
    @Test fun `at least 5 validated categories cover climate windows sunroof sunshade locks`() {
        // Load with empty competitor JSON — only LIVE_VALIDATED contributes
        val allowlist = WriteAllowlist.loadProduction { "{}" }
        val validatedCategories = allowlist.allEntries()
            .filter { it.validated }
            .map { it.category }
            .toSet()
        val required = setOf("climate", "windows", "sunroof", "sunshade", "locks")
        val missing = required - validatedCategories
        assertTrue("Missing validated categories: $missing", missing.isEmpty())
    }

    // ── Test 3: production has at least 100 entries ───────────────────────────
    @Test fun `production has at least 100 entries`() {
        // Read the real competitor JSON from the project research directory.
        // The test runs on the Mac where .research/ is available at project root.
        val projectRoot = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, ".research/competitor-v2/pushFidConfig.json").exists() }
            ?: error("Cannot find .research/competitor-v2/pushFidConfig.json from ${File(".").canonicalPath}")
        val src = File(projectRoot, ".research/competitor-v2/pushFidConfig.json")
        val raw = src.readText()
        // Extract defaults.actions slice (same logic as extract-competitor-actions.py)
        val fullJson = org.json.JSONObject(raw)
        val actionsJson = fullJson.getJSONObject("defaults").getJSONObject("actions").toString()
        val allowlist = WriteAllowlist.loadProduction { actionsJson }
        assertTrue(
            "Expected ≥ 100 entries, got ${allowlist.size}",
            allowlist.size >= 100
        )
    }

    // ── Test 4: live-validated entry wins on name collision ───────────────────
    @Test fun `validated entries override competitor entries on name collision`() {
        // doors_lock exists in LIVE_VALIDATED (dev=1001, fid=1276141590)
        // Competitor fixture has doors_lock with a different dev/fid — live wins
        val fixture = """
            {
              "doors_lock": { "featureId": 1234, "deviceType": 9999, "value": 99 }
            }
        """.trimIndent()
        val allowlist = WriteAllowlist.loadProduction { fixture }
        val entry = allowlist.find("doors_lock")
        assertNotNull(entry)
        assertEquals(
            "live-leopard3-2026-05-28 must win on collision",
            "live-leopard3-2026-05-28",
            entry!!.source,
        )
        assertEquals("live fid must be preserved", 1276141590, entry.writeFid)
    }

    // ── Legacy: EMPTY has no entries ─────────────────────────────────────────
    @Test fun `EMPTY has no entries`() {
        assertEquals(0, WriteAllowlist.EMPTY.size)
        assertNull(WriteAllowlist.EMPTY.find("anything"))
    }
}

package com.bydmate.app.data.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CommandTranslatorTest {

    // ── Test 1: prefix stripped before lookup ─────────────────────────────────
    @Test fun `prefix stripped before lookup`() {
        val r = CommandTranslator.resolve("迪加车门上锁")
        assertEquals("doors_lock", r?.actionName)
        assertEquals(2, r?.value)
    }

    // ── Test 2: unknown command returns null ──────────────────────────────────
    @Test fun `unknown command returns null`() {
        assertNull(CommandTranslator.resolve("不存在的命令"))
    }

    // ── Test 3: every translator action_name resolves in production allowlist ─
    // CI gate: translator + allowlist must stay in sync.
    @Test fun `every translator action_name resolves in production allowlist`() {
        val projectRoot = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/assets/competitor-actions.json").exists() }
            ?: error("Cannot find app/src/main/assets/competitor-actions.json from ${File(".").canonicalPath}")
        val jsonText = File(projectRoot, "app/src/main/assets/competitor-actions.json").readText()
        val allowlist = WriteAllowlist.loadProduction { jsonText }
        val unresolved = CommandTranslator.allActions().filter { allowlist.find(it) == null }
        assertTrue(
            "Translator references action_names not in allowlist: $unresolved",
            unresolved.isEmpty(),
        )
    }

    // ── Test 4: set temperature 22 maps to ac_temp_main val 22 ───────────────
    @Test fun `set temperature 22 maps to ac_temp_main val 22`() {
        val r = CommandTranslator.resolve("设置温度22")
        assertEquals("ac_temp_main", r?.actionName)
        assertEquals(22, r?.value)
    }

    // ── Test 5: command without prefix resolves directly ──────────────────────
    @Test fun `command without prefix resolves directly`() {
        val r = CommandTranslator.resolve("车门解锁")
        assertEquals("doors_unlock", r?.actionName)
        assertEquals(1, r?.value)
    }

    // ── Test 6: seat heat level 1 maps to _on val=2 ──────────────────────────
    @Test fun `seat heat level 1 maps to driver_seat_heat_on val 2`() {
        val r = CommandTranslator.resolve("主驾座椅加热1档")
        assertEquals("driver_seat_heat_on", r?.actionName)
        assertEquals(2, r?.value)
    }

    // ── Test 7: seat heat level 2 maps to _lvl2 val=3 ────────────────────────
    @Test fun `seat heat level 2 maps to driver_seat_heat_lvl2 val 3`() {
        val r = CommandTranslator.resolve("主驾座椅加热2档")
        assertEquals("driver_seat_heat_lvl2", r?.actionName)
        assertEquals(3, r?.value)
    }

    // ── Test 8: seat heat off maps to _off val=1 ─────────────────────────────
    @Test fun `seat heat off maps to driver_seat_heat_off val 1`() {
        val r = CommandTranslator.resolve("主驾座椅加热关闭")
        assertEquals("driver_seat_heat_off", r?.actionName)
        assertEquals(1, r?.value)
    }

    // ── Test 9: sunroof 50 maps to sunroof_tilt val=3 ────────────────────────
    @Test fun `sunroof 50 maps to sunroof_tilt val 3`() {
        val r = CommandTranslator.resolve("天窗打开50")
        assertEquals("sunroof_tilt", r?.actionName)
        assertEquals(3, r?.value)
    }

    // ── Test 10: inner circulation maps to ac_cycle_inner val=1 ──────────────
    @Test fun `inner circulation maps to ac_cycle_inner val 1`() {
        val r = CommandTranslator.resolve("内循环")
        assertEquals("ac_cycle_inner", r?.actionName)
        assertEquals(1, r?.value)
    }

    // ── Test 11: outer circulation maps to ac_cycle_outer val=2 ──────────────
    @Test fun `outer circulation maps to ac_cycle_outer val 2`() {
        val r = CommandTranslator.resolve("外循环")
        assertEquals("ac_cycle_outer", r?.actionName)
        assertEquals(2, r?.value)
    }

    // ── Test 12: allActions returns non-empty set of unique names ─────────────
    @Test fun `allActions returns non-empty set`() {
        val actions = CommandTranslator.allActions()
        assertTrue("allActions must be non-empty", actions.isNotEmpty())
    }
}

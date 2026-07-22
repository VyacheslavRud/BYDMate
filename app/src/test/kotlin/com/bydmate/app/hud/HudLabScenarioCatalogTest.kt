package com.bydmate.app.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HudLabScenarioCatalogTest {

    private val forbiddenFields = setOf(3, 4, 7, 8, 12, 17, 18, 21, 22, 23, 24, 25, 30, 31)
    private val allowedFieldOrder = listOf(2, 6, 9, 10, 11, 16, 26, 28, 33)

    private fun scenario(id: String): HudLabScenario =
        requireNotNull(HudLabScenarioCatalog.byId(id)) { "missing scenario $id" }

    private fun onlySend(id: String): HudLabScenarioStep.Send =
        scenario(id).steps.filterIsInstance<HudLabScenarioStep.Send>().single()

    private fun fieldNumbers(frame: HudLabFrameSpec): List<Int> =
        frame.fieldManifest.split(',').map { token ->
            require(token.startsWith("f")) { "unexpected manifest token $token" }
            token.substringAfter('f').substringBefore('=').toInt()
        }

    @Test
    fun `catalog keeps confirmed Sea Lion checks separate from compatibility probes`() {
        val confirmedIds = listOf("SL01", "SL02", "SL03", "SL04", "SL05")
        val compatibilityIds = listOf(
            "U01", "U02", "R01", "R02", "S01", "S02", "N17", "N18",
        )
        val extendedIds = listOf("HX01", "HX02", "HX03", "HX04", "HX05")

        assertEquals(confirmedIds, HudLabScenarioCatalog.confirmed.map(HudLabScenario::id))
        assertEquals(
            compatibilityIds,
            HudLabScenarioCatalog.compatibility.map(HudLabScenario::id),
        )
        assertEquals(
            confirmedIds + extendedIds + compatibilityIds +
                HudLabScenarioCatalog.explorer.map(HudLabScenario::id),
            HudLabScenarioCatalog.all.map(HudLabScenario::id),
        )
        assertEquals(extendedIds, HudLabScenarioCatalog.extended.map(HudLabScenario::id))
        assertEquals(59, HudLabScenarioCatalog.all.map(HudLabScenario::id).toSet().size)
        assertNull(HudLabScenarioCatalog.byId("X01"))
        assertNull(HudLabScenarioCatalog.byId("W01"))
    }

    @Test
    fun `explorer covers only unknown values from the bounded donor inventory`() {
        val expectedDonor = ((0..4) + (7..49)).toSet()
        val excluded = setOf(0, 1, 2, 3, 9, 13, 24)

        assertEquals(48, HudF28ExplorerCatalog.donorValues.size)
        assertEquals(expectedDonor, HudF28ExplorerCatalog.donorValues)
        assertEquals(excluded, HudF28ExplorerCatalog.alreadyTestedValues)
        assertEquals(41, HudF28ExplorerCatalog.candidates.size)
        assertTrue(HudF28ExplorerCatalog.candidates.none(excluded::contains))
        assertFalse(HudF28ExplorerCatalog.donorValues.contains(5))
        assertFalse(HudF28ExplorerCatalog.donorValues.contains(6))
        assertEquals("E04", HudF28ExplorerCatalog.scenarioId(4))
        assertEquals("E0A", HudF28ExplorerCatalog.scenarioId(10))
        assertEquals("E31", HudF28ExplorerCatalog.scenarioId(49))

        HudLabScenarioCatalog.explorer.forEach { explorer ->
            val send = explorer.steps.filterIsInstance<HudLabScenarioStep.Send>().single()
            assertEquals(HudLabScenarioGroup.F28_EXPLORER, explorer.group)
            assertEquals(HudLabObserved.NAMED_INDICATOR, explorer.expected)
            assertEquals(HudLabFrameSpec(f28 = send.frame.f28, distanceMeters = 50, road = ""), send.frame)
            assertEquals(3, send.repeatCount)
            assertEquals(300L, send.cadenceMs)
            assertNull(send.frame.iconCode)
            assertFalse(send.frame.includeSpeedSign)
        }
    }

    @Test
    fun `confirmed checks reproduce the minimal production contract`() {
        val right50 = onlySend("SL01").frame
        val left50 = onlySend("SL02").frame

        assertEquals(HudLabFrameSpec(f28 = 2, distanceMeters = 50, road = ""), right50)
        assertEquals(right50.copy(f28 = 3), left50)
        assertEquals(right50.copy(distanceMeters = 100), onlySend("SL03").frame)
        assertEquals(left50.copy(distanceMeters = 100), onlySend("SL04").frame)
        assertEquals(right50.copy(road = "HUD LAB ROAD"), onlySend("SL05").frame)

        assertEquals(HudLabObserved.RIGHT, scenario("SL01").expected)
        assertEquals(HudLabObserved.LEFT, scenario("SL02").expected)
        assertEquals(HudLabObserved.STRAIGHT, scenario("SL03").expected)
        assertEquals(HudLabObserved.STRAIGHT, scenario("SL04").expected)
        assertEquals(HudLabObserved.ROAD_VISIBLE, scenario("SL05").expected)

        HudLabScenarioCatalog.confirmed.forEach { confirmed ->
            val frame = onlySend(confirmed.id).frame
            assertEquals(1, frame.effectiveRenderClass)
            assertEquals(0, frame.speedLimit)
            assertNull(frame.etaString)
            assertEquals(0, frame.totalDistanceMeters)
            assertFalse(frame.includeSpeedSign)
            assertNull(frame.iconCode)
        }
    }

    @Test
    fun `older working compatibility probes retain their exact fields`() {
        val uturnFrames = listOf(onlySend("U01").frame, onlySend("U02").frame)
        assertEquals(listOf(20, 50), uturnFrames.map(HudLabFrameSpec::distanceMeters))
        uturnFrames.forEach { assertEquals(9, it.f28) }

        assertEquals(13, onlySend("R01").frame.f28)
        assertEquals(24, onlySend("R02").frame.f28)
        assertEquals(20, onlySend("R01").frame.distanceMeters)
        assertEquals(20, onlySend("R02").frame.distanceMeters)

        val speed50 = onlySend("S01").frame
        val speed80 = onlySend("S02").frame
        assertEquals(50, speed50.speedLimit)
        assertEquals(speed50.copy(speedLimit = 80), speed80)
        assertFalse(speed50.includeSpeedSign)
        assertFalse(speed80.includeSpeedSign)

        assertEquals(2, onlySend("N17").frame.f28)
        assertEquals(3, onlySend("N18").frame.f28)
        assertEquals(6, onlySend("N17").frame.effectiveRenderClass)
        assertEquals(50, onlySend("N17").frame.speedLimit)
        assertEquals(
            HudLabObserved.SPEED_NUMBER_WITH_MANEUVER_VISIBLE,
            scenario("N17").expected,
        )
    }

    @Test
    fun `Sea Lion extensions isolate scalar fields without PNG`() {
        val speed = onlySend("HX01").frame
        assertEquals(50, speed.speedLimit)
        assertNull(speed.etaString)
        assertEquals(0, speed.totalDistanceMeters)
        assertEquals(1, speed.effectiveRenderClass)

        val eta = onlySend("HX02").frame
        assertEquals("12:34", eta.etaString)
        assertEquals(0, eta.speedLimit)
        assertEquals(0, eta.totalDistanceMeters)

        val progress = onlySend("HX03").frame
        assertEquals(100, progress.totalDistanceMeters)
        assertNull(progress.etaString)
        assertEquals(0, progress.speedLimit)

        val full = onlySend("HX04").frame
        assertEquals("HUD LAB FULL", full.road)
        assertEquals("12:34", full.etaString)
        assertEquals(100, full.totalDistanceMeters)
        assertEquals(50, full.speedLimit)

        assertEquals(6, onlySend("HX05").frame.effectiveRenderClass)
        HudLabScenarioCatalog.extended.forEach { scenario ->
            val frame = onlySend(scenario.id).frame
            assertEquals(HudLabScenarioGroup.SEA_LION_EXTENDED, scenario.group)
            assertNull(frame.iconCode)
            assertFalse(frame.includeSpeedSign)
        }
    }

    @Test
    fun `all scenarios keep accepted cadence and bounded fields`() {
        HudLabScenarioCatalog.all.forEach { scenario ->
            assertEquals(2, scenario.steps.size)
            val clear = scenario.steps.first() as HudLabScenarioStep.Clear
            val send = scenario.steps.last() as HudLabScenarioStep.Send
            assertEquals(3, clear.attempts)
            assertEquals(if (scenario.group == HudLabScenarioGroup.F28_EXPLORER) 3 else 10, send.repeatCount)
            assertEquals(300L, send.cadenceMs)
            assertEquals(350L, send.gapBeforeMs)
            assertNull(send.frame.iconCode)
            assertFalse(send.frame.includeSpeedSign)
            assertTrue(send.frame.effectiveRenderClass in setOf(1, 6))

            val fields = fieldNumbers(send.frame)
            val canonicalIndexes = fields.map(allowedFieldOrder::indexOf)
            assertTrue("unknown field in ${send.label}: $fields", canonicalIndexes.all { it >= 0 })
            assertEquals(canonicalIndexes.sorted(), canonicalIndexes)
            assertEquals(fields.size, fields.toSet().size)
            assertTrue(fields.none(forbiddenFields::contains))
        }
    }
}

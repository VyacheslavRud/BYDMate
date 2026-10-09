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
        assertEquals(confirmedIds, HudLabScenarioCatalog.confirmed.map(HudLabScenario::id))
        assertEquals(
            compatibilityIds,
            HudLabScenarioCatalog.compatibility.map(HudLabScenario::id),
        )
        assertEquals(
            confirmedIds + compatibilityIds,
            HudLabScenarioCatalog.all.map(HudLabScenario::id),
        )
        assertEquals(13, HudLabScenarioCatalog.all.map(HudLabScenario::id).toSet().size)
        assertNull(HudLabScenarioCatalog.byId("HX01"))
        assertNull(HudLabScenarioCatalog.byId("HX05"))
        assertNull(HudLabScenarioCatalog.byId("X01"))
        assertNull(HudLabScenarioCatalog.byId("W01"))
    }

    @Test
    fun `retired explorer IDs are readable as history but cannot run`() {
        HudF28ExplorerCatalog.candidates.forEach { raw ->
            val id = HudF28ExplorerCatalog.scenarioId(raw)
            assertTrue(HudF28ExplorerCatalog.isHistoricalScenario(id))
            assertNull(HudLabScenarioCatalog.byId(id))
        }
        assertFalse(HudF28ExplorerCatalog.isHistoricalScenario("E05"))
        assertFalse(HudF28ExplorerCatalog.isHistoricalScenario("SL01"))
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
    fun `all scenarios keep accepted cadence and bounded fields`() {
        HudLabScenarioCatalog.all.forEach { scenario ->
            assertEquals(2, scenario.steps.size)
            val clear = scenario.steps.first() as HudLabScenarioStep.Clear
            val send = scenario.steps.last() as HudLabScenarioStep.Send
            assertEquals(3, clear.attempts)
            assertEquals(10, send.repeatCount)
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

    @Test
    fun `roundabout search catalog is separate, unique and runnable by id`() {
        val ids = HudLabScenarioCatalog.search.map(HudLabScenario::id)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.none { id -> HudLabScenarioCatalog.all.any { it.id == id } })
        ids.forEach { assertEquals(it, scenario(it).id) }
        assertEquals(listOf("T01", "T02", "T03"), ids.take(3))
        assertEquals(listOf("P01", "P02", "P03"), ids.takeLast(3))
    }

    @Test
    fun `framing probe sends a plain right arrow with byd-hud framing`() {
        val frame = onlySend("C01").frame
        assertEquals(2, frame.f28)
        assertEquals(50, frame.distanceMeters)
        assertNull(frame.iconCode)
        assertTrue(frame.f2Counter && frame.wallClockTimestamp)
        assertEquals(HudLabObserved.RIGHT, scenario("C01").expected)
    }

    @Test
    fun `transition probes change only the arrow and never send a CLEAR between phases`() {
        val expected = mapOf(
            "T01" to (1 to 2),
            "T02" to (11 to 2),
            "T03" to (2 to 11),
        )
        expected.forEach { (id, arrows) ->
            val steps = scenario(id).steps
            assertEquals(3, steps.size)
            assertTrue(steps[0] is HudLabScenarioStep.Clear)
            val first = steps[1] as HudLabScenarioStep.Send
            val second = steps[2] as HudLabScenarioStep.Send
            assertEquals(arrows.first, first.frame.f28)
            assertEquals(arrows.second, second.frame.f28)
            listOf(first, second).forEach { send ->
                assertNull(send.frame.iconCode)
                assertFalse(send.frame.f2Counter)
                assertFalse(send.frame.wallClockTimestamp)
            }
            assertEquals(HudLabObserved.ARROW_CHANGED, scenario(id).expected)
        }
    }

    @Test
    fun `selector probes cover the search values at the firmware turn distance`() {
        val probes = HudLabScenarioCatalog.search.filter { it.id.startsWith("K") }
        assertEquals(
            HudLabScenarioCatalog.SEARCH_F28_VALUES,
            probes.map { (it.steps.last() as HudLabScenarioStep.Send).frame.f28 },
        )
        probes.forEach { probe ->
            val frame = (probe.steps.last() as HudLabScenarioStep.Send).frame
            assertEquals(50, frame.distanceMeters)
            assertNull(frame.iconCode)
            assertEquals(HudLabObserved.ROUNDABOUT, probe.expected)
        }
    }

    @Test
    fun `picture probes vary one framing variable at a time`() {
        fun frame(id: String) = onlySend(id).frame
        listOf("P01", "P02", "P03").forEach { id ->
            assertEquals(HudLabScenarioCatalog.SEARCH_ROUNDABOUT_ICON, frame(id).iconCode)
            assertEquals(99, frame(id).f28)
        }
        assertTrue(frame("P01").f2Counter && frame("P01").wallClockTimestamp)
        assertTrue(!frame("P02").f2Counter && frame("P02").wallClockTimestamp)
        assertTrue(frame("P03").f2Counter && !frame("P03").wallClockTimestamp)
    }

    @Test
    fun `every search frame passes the lab encoder bounds`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        HudLabScenarioCatalog.search.forEach { scenario ->
            scenario.steps.filterIsInstance<HudLabScenarioStep.Send>().forEach { send ->
                val icon = if (send.frame.iconCode != null) png else null
                val payload = HudProtobufBuilder.buildHudLabScenarioFrame(send.frame, icon, null, 7)
                assertTrue(payload.isNotEmpty())
            }
        }
    }

    @Test
    fun `field probes are unique, separate and answerable`() {
        val ids = HudLabScenarioCatalog.fields.map(HudLabScenario::id)
        assertEquals(ids.size, ids.toSet().size)
        val elsewhere = (HudLabScenarioCatalog.all + HudLabScenarioCatalog.search).map(HudLabScenario::id)
        assertTrue(ids.none(elsewhere::contains))
        assertEquals("C01", ids.first())
        HudLabScenarioCatalog.fields.forEach { probe ->
            assertEquals(probe.id, scenario(probe.id).id)
            assertTrue(probe.id, probe.observations.isNotEmpty())
            assertTrue(probe.id, probe.observations.contains(HudLabObserved.NOT_REPORTED))
            assertEquals(HudLabScenarioGroup.FIELD_PROBE, probe.group)
        }
    }

    @Test
    fun `every field probe passes the lab encoder and keeps fields in order`() {
        HudLabScenarioCatalog.fields.forEach { probe ->
            val send = onlySend(probe.id)
            val payload = HudProtobufBuilder.buildHudLabScenarioFrame(send.frame, null, null, 3)
            assertTrue(probe.id, payload.isNotEmpty())
            val manifestFields = fieldNumbers(send.frame)
            assertEquals(probe.id, manifestFields.sorted(), manifestFields)
            assertEquals(probe.id, manifestFields.size, manifestFields.toSet().size)
        }
    }

    @Test
    fun `field probes cover the untested schema fields`() {
        val sent = HudLabScenarioCatalog.fields
            .flatMap { onlySend(it.id).frame.extras.map(HudLabExtraField::field) }
            .toSet()
        assertEquals(setOf(3, 4, 5, 7, 8, 12, 13, 14, 15, 17, 18, 23, 24, 25, 27, 29), sent)
        assertTrue(onlySend("F07").frame.omitDistance)
        assertEquals(5, onlySend("F06").frame.distanceMeters)
    }
}

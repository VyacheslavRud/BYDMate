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
    fun `catalog exposes six compact unknown-only scenarios`() {
        val expectedIds = listOf("U01", "U02", "R01", "R02", "S01", "S02")
        val actualIds = HudLabScenarioCatalog.all.map(HudLabScenario::id)

        assertEquals(expectedIds, actualIds)
        assertEquals(expectedIds.size, actualIds.toSet().size)
        assertNull(HudLabScenarioCatalog.byId("W01"))
    }

    @Test
    fun `groups contain only remaining calibration targets`() {
        val expected = mapOf(
            HudLabScenarioGroup.UTURN to listOf("U01", "U02"),
            HudLabScenarioGroup.ROUNDABOUT to listOf("R01", "R02"),
            HudLabScenarioGroup.SPEED_LIMIT to listOf("S01", "S02"),
            HudLabScenarioGroup.CONTROL to emptyList(),
        )

        expected.forEach { (group, ids) ->
            assertEquals(
                ids,
                HudLabScenarioCatalog.all.filter { it.group == group }.map(HudLabScenario::id),
            )
        }
    }

    @Test
    fun `uturn candidates keep raw nine and compare close distances`() {
        val frames = listOf(onlySend("U01").frame, onlySend("U02").frame)

        assertEquals(listOf(20, 50), frames.map(HudLabFrameSpec::distanceMeters))
        frames.forEach { frame -> assertEquals(9, frame.f28) }
        assertTrue(listOf("U01", "U02").all { scenario(it).expected == HudLabObserved.UTURN })
    }

    @Test
    fun `roundabout candidates test enter and exit codes without PNG`() {
        val enter = onlySend("R01").frame
        val exit = onlySend("R02").frame

        assertEquals(13, enter.f28)
        assertEquals(24, exit.f28)
        assertEquals(20, enter.distanceMeters)
        assertEquals(
            enter.copy(f28 = 24, road = "HUD LAB ROUNDABOUT_EXIT"),
            exit,
        )
        assertEquals(HudLabCommand.ROUNDABOUT_ENTER, scenario("R01").command)
        assertEquals(HudLabCommand.ROUNDABOUT_EXIT, scenario("R02").command)
        assertEquals(HudLabObserved.ROUNDABOUT, scenario("R01").expected)
        assertEquals(HudLabObserved.ROUNDABOUT, scenario("R02").expected)
    }

    @Test
    fun `speed candidates change only f11 value`() {
        val fifty = onlySend("S01").frame
        val eighty = onlySend("S02").frame

        assertEquals(50, fifty.speedLimit)
        assertEquals(fifty.copy(speedLimit = 80), eighty)
        assertFalse(fifty.includeSpeedSign)
        assertFalse(eighty.includeSpeedSign)
        assertEquals(HudLabObserved.SPEED_NUMBER_VISIBLE, scenario("S01").expected)
    }

    @Test
    fun `all scenarios use accepted plain cadence and bounded fields`() {
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

            val fields = fieldNumbers(send.frame)
            val canonicalIndexes = fields.map(allowedFieldOrder::indexOf)
            assertTrue("unknown field in ${send.label}: $fields", canonicalIndexes.all { it >= 0 })
            assertEquals(canonicalIndexes.sorted(), canonicalIndexes)
            assertEquals(fields.size, fields.toSet().size)
            assertTrue(fields.none(forbiddenFields::contains))
        }
    }
}

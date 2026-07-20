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
    fun `catalog keeps current research separate from repeat calibration`() {
        val currentIds = (1..18).map { "X%02d".format(it) }
        val calibrationIds = listOf(
            "U01", "U02", "R01", "R02", "S01", "S02", "N17", "N18",
        )

        assertEquals(currentIds, HudLabScenarioCatalog.current.map(HudLabScenario::id))
        assertEquals(calibrationIds, HudLabScenarioCatalog.calibration.map(HudLabScenario::id))
        assertEquals(currentIds + calibrationIds, HudLabScenarioCatalog.all.map(HudLabScenario::id))
        assertEquals(26, HudLabScenarioCatalog.all.map(HudLabScenario::id).toSet().size)
        assertNull(HudLabScenarioCatalog.byId("W01"))
    }

    @Test
    fun `follow-up research isolates dependencies around confirmed speed and maneuver frames`() {
        val frames = (1..18).associate { number ->
            val id = "X%02d".format(number)
            id to onlySend(id).frame
        }
        val confirmedRight = onlySend("N17").frame
        val confirmedLeft = onlySend("N18").frame

        assertEquals(confirmedRight.copy(renderClass = null), frames.getValue("X01"))
        assertEquals(confirmedLeft.copy(renderClass = null), frames.getValue("X02"))
        assertEquals(confirmedRight.copy(speedLimit = 80), frames.getValue("X03"))
        assertEquals(confirmedLeft.copy(speedLimit = 80), frames.getValue("X04"))
        assertEquals(confirmedRight.copy(distanceMeters = 20), frames.getValue("X05"))
        assertEquals(confirmedRight.copy(distanceMeters = 100), frames.getValue("X06"))
        assertEquals(confirmedLeft.copy(distanceMeters = 20), frames.getValue("X07"))
        assertEquals(confirmedLeft.copy(distanceMeters = 100), frames.getValue("X08"))
        assertEquals(confirmedRight.copy(road = "HUD LAB SPEED"), frames.getValue("X09"))
        assertEquals(confirmedRight.copy(etaString = "12:34"), frames.getValue("X10"))
        assertEquals(confirmedRight.copy(totalDistanceMeters = 100), frames.getValue("X11"))
        assertEquals(
            confirmedLeft.copy(
                road = "HUD LAB FULL",
                etaString = "12:34",
                totalDistanceMeters = 100,
            ),
            frames.getValue("X12"),
        )
        assertEquals(confirmedRight.copy(f28 = 0), frames.getValue("X13"))
        assertEquals(confirmedRight.copy(f28 = 9), frames.getValue("X14"))
        assertEquals(confirmedRight, frames.getValue("X15"))
        assertEquals(confirmedRight, frames.getValue("X16"))
        assertEquals(confirmedRight.copy(speedLimit = 0), frames.getValue("X17"))
        assertEquals(confirmedLeft.copy(speedLimit = 0), frames.getValue("X18"))
    }

    @Test
    fun `previous six calibration scenarios retain their exact fields`() {
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
    fun `all scenarios keep accepted cadence and bounded scalar fields`() {
        HudLabScenarioCatalog.all.forEach { scenario ->
            assertEquals(2, scenario.steps.size)
            val clear = scenario.steps.first() as HudLabScenarioStep.Clear
            val send = scenario.steps.last() as HudLabScenarioStep.Send
            assertEquals(3, clear.attempts)
            val expectedDelivery = when (scenario.id) {
                "X15" -> 1 to 0L
                "X16" -> 6 to 500L
                else -> 10 to 300L
            }
            assertEquals(expectedDelivery.first, send.repeatCount)
            assertEquals(expectedDelivery.second, send.cadenceMs)
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

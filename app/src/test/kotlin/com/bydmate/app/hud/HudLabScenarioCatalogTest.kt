package com.bydmate.app.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HudLabScenarioCatalogTest {

    private val forbiddenFields = setOf(3, 4, 12, 17, 18, 21, 22, 23, 24, 25, 30, 31)
    private val allowedFieldOrder = listOf(2, 6, 7, 8, 9, 10, 11, 16, 26, 28, 33)

    private fun scenario(id: String): HudLabScenario =
        requireNotNull(HudLabScenarioCatalog.byId(id)) { "missing scenario $id" }

    private fun sends(id: String): List<HudLabScenarioStep.Send> =
        scenario(id).steps.filterIsInstance<HudLabScenarioStep.Send>()

    private fun onlySend(id: String): HudLabScenarioStep.Send =
        sends(id).single()

    private fun fieldNumbers(frame: HudLabFrameSpec): List<Int> =
        frame.fieldManifest.split(',').map { token ->
            require(token.startsWith("f")) { "unexpected manifest token $token" }
            token.substringAfter('f').substringBefore('=').toInt()
        }

    @Test
    fun `catalog exposes stable unique W01 through W36 ids`() {
        val expectedIds = (1..36).map { "W%02d".format(it) }
        val actualIds = HudLabScenarioCatalog.all.map(HudLabScenario::id)

        assertEquals(expectedIds, actualIds)
        assertEquals(expectedIds.size, actualIds.toSet().size)
        expectedIds.forEach { id ->
            assertEquals(id, HudLabScenarioCatalog.byId(id)?.id)
        }
        assertNull(HudLabScenarioCatalog.byId("W00"))
        assertNull(HudLabScenarioCatalog.byId("W37"))
    }

    @Test
    fun `group boundaries remain stable`() {
        val expected = mapOf(
            HudLabScenarioGroup.CONTROL to (1..3),
            HudLabScenarioGroup.RAW_F28 to (4..7),
            HudLabScenarioGroup.PNG_F8 to (8..12),
            HudLabScenarioGroup.MATCHED to (13..16),
            HudLabScenarioGroup.CROSSED to (17..18),
            HudLabScenarioGroup.FULL to (19..25),
            HudLabScenarioGroup.FIELD_ISOLATION to (26..31),
            HudLabScenarioGroup.TIMING to (32..36),
        )

        expected.forEach { (group, range) ->
            assertEquals(
                range.map { "W%02d".format(it) },
                HudLabScenarioCatalog.all.filter { it.group == group }.map(HudLabScenario::id),
            )
        }
    }

    @Test
    fun `all synthetic frames use only bounded donor values`() {
        val frames = HudLabScenarioCatalog.all
            .flatMap(HudLabScenario::steps)
            .filterIsInstance<HudLabScenarioStep.Send>()
            .map(HudLabScenarioStep.Send::frame)

        assertTrue(frames.isNotEmpty())
        frames.forEach { frame ->
            assertTrue("unsupported f28=${frame.f28}", frame.f28 == null || frame.f28 in setOf(1, 2, 3, 9))
            assertTrue(
                "unsupported donor icon=${frame.iconCode}",
                frame.iconCode == null || frame.iconCode in setOf(0, 1, 2, 9, 11),
            )
            assertTrue("negative distance", frame.distanceMeters >= 0)
            assertTrue("negative total distance", frame.totalDistanceMeters >= 0)
            assertTrue("road exceeds protobuf bound", frame.road.length <= HudProtobufBuilder.MAX_ROAD_CHARS)
            assertTrue(
                "ETA exceeds protobuf bound",
                frame.etaString == null || frame.etaString.length <= HudProtobufBuilder.MAX_ETA_CHARS,
            )
            assertTrue(
                "speed limit outside lab matrix: ${frame.speedLimit}",
                frame.speedLimit in setOf(0, 60),
            )
            assertTrue(!frame.includeSpeedSign || frame.speedLimit > 0)
        }
    }

    @Test
    fun `manifest follows protobuf donor order and excludes every dangerous field`() {
        HudLabScenarioCatalog.all
            .flatMap(HudLabScenario::steps)
            .filterIsInstance<HudLabScenarioStep.Send>()
            .forEach { send ->
                val fields = fieldNumbers(send.frame)
                val canonicalIndexes = fields.map(allowedFieldOrder::indexOf)

                assertTrue("unknown field in ${send.label}: $fields", canonicalIndexes.all { it >= 0 })
                assertEquals(
                    "field order differs from donor order in ${send.label}",
                    canonicalIndexes.sorted(),
                    canonicalIndexes,
                )
                assertEquals("duplicate field in ${send.label}", fields.size, fields.toSet().size)
                assertTrue("f2 must always be present", 2 in fields)
                assertTrue("f16 must always be present", 16 in fields)
                assertTrue("f9 must always be present", 9 in fields)
                assertTrue("f33 must always be present", 33 in fields)
                assertTrue("dangerous field in ${send.label}: $fields", fields.none(forbiddenFields::contains))
                forbiddenFields.forEach { forbidden ->
                    assertFalse("f$forbidden leaked into manifest", "f$forbidden=" in send.frame.fieldManifest)
                }
            }
    }

    @Test
    fun `manifest presence and constants exactly match each frame spec`() {
        HudLabScenarioCatalog.all
            .flatMap(HudLabScenario::steps)
            .filterIsInstance<HudLabScenarioStep.Send>()
            .forEach { send ->
                val frame = send.frame
                val fields = fieldNumbers(frame).toSet()
                val tokens = frame.fieldManifest.split(',').toSet()

                assertTrue("f2 donor constant changed", "f2=2" in tokens)
                assertTrue("f16 donor constant changed", "f16=2" in tokens)
                assertEquals(if (frame.includeSpeedSign) "f6=6" else "f6=1", tokens.first { it.startsWith("f6=") })
                assertEquals(frame.includeSpeedSign, 7 in fields)
                assertEquals(frame.iconCode != null, 8 in fields)
                assertEquals(frame.road.isNotEmpty(), 10 in fields)
                assertEquals(frame.speedLimit > 0, 11 in fields)
                assertEquals(frame.etaString != null, 26 in fields)
                assertEquals(frame.f28 != null, 28 in fields)
            }
    }

    @Test
    fun `canonical single and burst timing is exact`() {
        val clearOnly = scenario("W01").steps.single() as HudLabScenarioStep.Clear
        assertEquals(3, clearOnly.attempts)
        assertEquals(0L, clearOnly.gapBeforeMs)

        val single = scenario("W02").steps.single() as HudLabScenarioStep.Send
        assertEquals(1, single.repeatCount)
        assertEquals(0L, single.cadenceMs)
        assertEquals(0L, single.gapBeforeMs)

        ((3..23) + (26..31)).forEach { number ->
            val id = "W%02d".format(number)
            val steps = scenario(id).steps
            assertEquals("$id must pre-clear and then send one burst", 2, steps.size)
            val clear = steps[0] as HudLabScenarioStep.Clear
            val burst = steps[1] as HudLabScenarioStep.Send
            assertEquals(3, clear.attempts)
            assertEquals(0L, clear.gapBeforeMs)
            assertEquals(10, burst.repeatCount)
            assertEquals(300L, burst.cadenceMs)
            assertEquals(350L, burst.gapBeforeMs)
        }
    }

    @Test
    fun `raw PNG and matching direction scenarios retain exact donor semantics`() {
        val raw = mapOf(
            "W04" to (HudLabCommand.STRAIGHT to 1),
            "W05" to (HudLabCommand.RIGHT to 2),
            "W06" to (HudLabCommand.LEFT to 3),
            "W07" to (HudLabCommand.UTURN to 9),
        )
        raw.forEach { (id, expected) ->
            val frame = onlySend(id).frame
            assertEquals(expected.first, scenario(id).command)
            assertEquals(expected.second, frame.f28)
            assertNull(frame.iconCode)
        }

        val png = mapOf(
            "W08" to (null to 0),
            "W09" to (HudLabCommand.LEFT to 1),
            "W10" to (HudLabCommand.RIGHT to 2),
            "W11" to (HudLabCommand.STRAIGHT to 11),
            "W12" to (HudLabCommand.UTURN to 9),
        )
        png.forEach { (id, expected) ->
            val frame = onlySend(id).frame
            assertEquals(expected.first, scenario(id).command)
            assertNull(frame.f28)
            assertEquals(expected.second, frame.iconCode)
        }

        val matched = mapOf(
            "W13" to Triple(HudLabCommand.LEFT, 1, 3),
            "W14" to Triple(HudLabCommand.RIGHT, 2, 2),
            "W15" to Triple(HudLabCommand.STRAIGHT, 11, 1),
            "W16" to Triple(HudLabCommand.UTURN, 9, 9),
        )
        matched.forEach { (id, expected) ->
            val frame = onlySend(id).frame
            assertEquals(expected.first, scenario(id).command)
            assertEquals(expected.second, frame.iconCode)
            assertEquals(expected.third, frame.f28)
        }
    }

    @Test
    fun `crossed scenarios disagree only in maneuver sources`() {
        val leftPngRightMetadata = onlySend("W17").frame
        assertEquals(1, leftPngRightMetadata.iconCode)
        assertEquals(2, leftPngRightMetadata.f28)

        val rightPngLeftMetadata = onlySend("W18").frame
        assertEquals(2, rightPngLeftMetadata.iconCode)
        assertEquals(3, rightPngLeftMetadata.f28)

        assertEquals(
            leftPngRightMetadata.copy(iconCode = rightPngLeftMetadata.iconCode, f28 = rightPngLeftMetadata.f28),
            rightPngLeftMetadata,
        )
    }

    @Test
    fun `information progress speed and full donor scenarios are isolated`() {
        val info = onlySend("W19").frame
        assertEquals(500, info.distanceMeters)
        assertEquals("HUD LAB", info.road)
        assertEquals("12:34", info.etaString)
        assertEquals(0, info.totalDistanceMeters)
        assertNull(info.iconCode)
        assertNull(info.f28)

        val progress = onlySend("W20").frame
        assertEquals(info.copy(totalDistanceMeters = 1_000), progress)
        assertTrue("f33=0.5" in progress.fieldManifest)

        val speed = onlySend("W21").frame
        assertEquals(60, speed.speedLimit)
        assertTrue(speed.includeSpeedSign)
        assertNull(speed.iconCode)
        assertNull(speed.f28)

        val fullLeft = onlySend("W22").frame
        assertEquals(1, fullLeft.iconCode)
        assertEquals(3, fullLeft.f28)
        assertEquals(500, fullLeft.distanceMeters)
        assertEquals(1_000, fullLeft.totalDistanceMeters)
        assertEquals(60, fullLeft.speedLimit)
        assertTrue(fullLeft.includeSpeedSign)

        val fullRight = onlySend("W23").frame
        assertEquals(fullLeft.copy(iconCode = 2, f28 = 2), fullRight)
    }

    @Test
    fun `W26 through W31 isolate every additional information field`() {
        val distance = onlySend("W26").frame
        assertEquals(HudLabFrameSpec(distanceMeters = 500, road = ""), distance)
        assertEquals(HudLabObserved.DISTANCE_VISIBLE, scenario("W26").expected)

        val road = onlySend("W27").frame
        assertEquals(0, road.distanceMeters)
        assertEquals("HUD LAB ROAD", road.road)
        assertNull(road.etaString)
        assertEquals(HudLabObserved.ROAD_VISIBLE, scenario("W27").expected)

        val eta = onlySend("W28").frame
        assertEquals(0, eta.distanceMeters)
        assertEquals("", eta.road)
        assertEquals("12:34", eta.etaString)
        assertEquals(HudLabObserved.ETA_VISIBLE, scenario("W28").expected)

        val progress = onlySend("W29").frame
        assertEquals(distance.copy(totalDistanceMeters = 1_000), progress)
        assertTrue("f33=0.5" in progress.fieldManifest)
        assertEquals(HudLabObserved.PROGRESS_VISIBLE, scenario("W29").expected)

        val numberOnly = onlySend("W30").frame
        assertEquals(60, numberOnly.speedLimit)
        assertFalse(numberOnly.includeSpeedSign)
        assertFalse("f7=speed_png" in numberOnly.fieldManifest)
        assertTrue("f11=60" in numberOnly.fieldManifest)
        assertEquals(HudLabObserved.SPEED_NUMBER_VISIBLE, scenario("W30").expected)

        val sign = onlySend("W31").frame
        assertEquals(numberOnly.copy(includeSpeedSign = true), sign)
        assertTrue("f7=speed_png" in sign.fieldManifest)
        assertEquals(HudLabObserved.SPEED_SIGN_VISIBLE, scenario("W31").expected)
    }

    @Test
    fun `W32 through W36 isolate single and cadence delivery`() {
        val expected = mapOf(
            "W32" to Triple(1, 0L, HudLabFrameSpec(f28 = 2)),
            "W33" to Triple(1, 0L, HudLabFrameSpec(iconCode = 2)),
            "W34" to Triple(1, 0L, HudLabFrameSpec(f28 = 2, iconCode = 2)),
            "W35" to Triple(10, 100L, HudLabFrameSpec(f28 = 2, iconCode = 2)),
            "W36" to Triple(6, 500L, HudLabFrameSpec(f28 = 2, iconCode = 2)),
        )
        expected.forEach { (id, contract) ->
            val steps = scenario(id).steps
            assertEquals(2, steps.size)
            assertEquals(3, (steps.first() as HudLabScenarioStep.Clear).attempts)
            val send = steps.last() as HudLabScenarioStep.Send
            assertEquals(contract.first, send.repeatCount)
            assertEquals(contract.second, send.cadenceMs)
            assertEquals(contract.third, send.frame)
            assertEquals(350L, send.gapBeforeMs)
            assertEquals(HudLabObserved.RIGHT, scenario(id).expected)
        }
    }

    @Test
    fun `W24 changes left to right without an intermediate clear`() {
        val steps = scenario("W24").steps
        assertEquals(HudLabObserved.LEFT_THEN_RIGHT, scenario("W24").expected)
        assertEquals(3, steps.size)
        assertTrue(steps[0] is HudLabScenarioStep.Clear)
        assertTrue(steps.drop(1).none { it is HudLabScenarioStep.Clear })

        val left = steps[1] as HudLabScenarioStep.Send
        val right = steps[2] as HudLabScenarioStep.Send
        assertEquals("transition_left", left.label)
        assertEquals(1, left.frame.iconCode)
        assertEquals(3, left.frame.f28)
        assertEquals(350L, left.gapBeforeMs)
        assertEquals("transition_right", right.label)
        assertEquals(2, right.frame.iconCode)
        assertEquals(2, right.frame.f28)
        assertEquals(300L, right.gapBeforeMs)
        listOf(left, right).forEach { send ->
            assertEquals(10, send.repeatCount)
            assertEquals(300L, send.cadenceMs)
        }
    }

    @Test
    fun `W25 clears between identical right bursts and redraws after guard gap`() {
        val steps = scenario("W25").steps
        assertEquals(HudLabObserved.RIGHT_CLEARED_AND_REDRAWN, scenario("W25").expected)
        assertEquals(4, steps.size)
        assertTrue(steps[0] is HudLabScenarioStep.Clear)
        assertTrue(steps[1] is HudLabScenarioStep.Send)
        assertTrue(steps[2] is HudLabScenarioStep.Clear)
        assertTrue(steps[3] is HudLabScenarioStep.Send)

        val before = steps[1] as HudLabScenarioStep.Send
        val middleClear = steps[2] as HudLabScenarioStep.Clear
        val after = steps[3] as HudLabScenarioStep.Send
        assertEquals("redraw_before", before.label)
        assertEquals("redraw_after", after.label)
        assertEquals(before.frame, after.frame)
        assertEquals(2, before.frame.iconCode)
        assertEquals(2, before.frame.f28)
        assertEquals(10, before.repeatCount)
        assertEquals(10, after.repeatCount)
        assertEquals(300L, before.cadenceMs)
        assertEquals(300L, after.cadenceMs)
        assertEquals(3, middleClear.attempts)
        assertEquals(300L, middleClear.gapBeforeMs)
        assertEquals(350L, after.gapBeforeMs)
    }

    @Test
    fun `every send and clear operation has a bounded positive contract`() {
        HudLabScenarioCatalog.all.forEach { scenario ->
            assertTrue("${scenario.id} has no steps", scenario.steps.isNotEmpty())
            assertTrue("${scenario.id} summary is empty", scenario.summary.isNotBlank())
            scenario.steps.forEach { step ->
                assertTrue("negative gap in ${scenario.id}", step.gapBeforeMs >= 0L)
                when (step) {
                    is HudLabScenarioStep.Clear -> assertTrue(step.attempts in 1..3)
                    is HudLabScenarioStep.Send -> {
                        assertTrue(step.label.isNotBlank())
                        assertTrue(step.repeatCount in 1..10)
                        assertTrue(step.cadenceMs in setOf(0L, 100L, 300L, 500L))
                        if (step.repeatCount > 1) assertTrue(step.cadenceMs > 0L)
                    }
                }
            }
        }
    }
}

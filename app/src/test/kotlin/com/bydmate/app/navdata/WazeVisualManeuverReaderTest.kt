package com.bydmate.app.navdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WazeVisualManeuverReaderTest {
    private fun roundaboutMask(exit: Int, withStem: Boolean = true): BooleanArray {
        val mask = BooleanArray(100 * 100)
        for (y in 0 until 100) for (x in 0 until 100) {
            val radiusSquared = (x - 50) * (x - 50) + (y - 38) * (y - 38)
            if (radiusSquared in 12 * 12..20 * 20) mask[y * 100 + x] = true
            if (withStem && x in 46..54 && y in 54..91) mask[y * 100 + x] = true
            if (withStem && exit != 0 && y in 34..42 &&
                (if (exit > 0) x in 67..88 else x in 12..33)
            ) mask[y * 100 + x] = true
            if (withStem && exit == 0 && x in 46..54 && y in 8..21) mask[y * 100 + x] = true
        }
        return mask
    }

    @Test fun `roundabout exits keep their ring instead of becoming ordinary arrows`() {
        listOf(-1, 0, 1).forEach { exit ->
            assertEquals(NavManeuverCodes.GAODE_ROUNDABOUT_ENTER,
                WazeVisualManeuverReader.classifyForegroundMask(100, 100, roundaboutMask(exit))?.maneuverGaode)
        }
    }

    @Test fun `roundabout survives coloured badge and disconnected exit number`() {
        listOf(0xfff7f7f7.toInt(), 0xff151515.toInt()).forEach { ink ->
            val pixels = IntArray(100 * 100) { 0xff909090.toInt() }
            for (y in 0 until 100) for (x in 0 until 100) {
                if ((x - 50) * (x - 50) + (y - 50) * (y - 50) <= 46 * 46) {
                    pixels[y * 100 + x] = 0xff7356a8.toInt()
                }
            }
            roundaboutMask(1).forEachIndexed { index, arrow -> if (arrow) pixels[index] = ink }
            for (y in 34..42) for (x in 49..51) pixels[y * 100 + x] = ink
            assertEquals(NavManeuverCodes.GAODE_ROUNDABOUT_ENTER,
                WazeVisualManeuverReader.classifyPixels(100, 100, pixels)?.maneuverGaode)
        }
    }

    @Test fun `outlined badge without approach stem is not a roundabout`() {
        assertEquals(0, WazeVisualManeuverReader.classifyForegroundMask(
            100, 100, roundaboutMask(0, withStem = false),
        )?.maneuverGaode)
    }

    @Test fun `open U-turn does not become a roundabout`() {
        val mask = BooleanArray(100 * 100)
        for (y in 20..85) for (x in 30..70) {
            if (x in 30..38 || (x in 62..70 && y <= 60) || y <= 28) mask[y * 100 + x] = true
        }
        assertTrue(WazeVisualManeuverReader.classifyForegroundMask(100, 100, mask)?.maneuverGaode !=
            NavManeuverCodes.GAODE_ROUNDABOUT_ENTER)
    }

    private fun turnMask(right: Boolean): BooleanArray {
        val width = 100
        val mask = BooleanArray(width * width)
        fun fill(left: Int, top: Int, rightEdge: Int, bottom: Int) {
            for (y in top until bottom) for (x in left until rightEdge) mask[y * width + x] = true
        }
        fill(45, 38, 55, 92)
        if (right) {
            fill(45, 28, 82, 43)
            for (i in 0 until 20) fill(72 + i / 2, 18 + i, 78 + i / 2, 20 + i)
        } else {
            fill(18, 28, 55, 43)
            for (i in 0 until 20) fill(22 - i / 2, 18 + i, 28 - i / 2, 20 + i)
        }
        return mask
    }

    @Test fun `connected right arrow shape maps to Waze right`() {
        val result = WazeVisualManeuverReader.classifyForegroundMask(100, 100, turnMask(true))

        assertEquals(NavManeuverCodes.GAODE_RIGHT, result?.maneuverGaode)
        assertTrue((result?.horizontalShift ?: 0f) > 0f)
    }

    @Test fun `connected left arrow shape maps to Waze left`() {
        val result = WazeVisualManeuverReader.classifyForegroundMask(100, 100, turnMask(false))

        assertEquals(NavManeuverCodes.GAODE_LEFT, result?.maneuverGaode)
        assertTrue((result?.horizontalShift ?: 0f) < 0f)
    }

    @Test fun `straight symmetric arrow maps to straight without inventing a side`() {
        val mask = BooleanArray(100 * 100)
        for (y in 15 until 92) for (x in 45 until 55) mask[y * 100 + x] = true
        for (i in 0 until 18) {
            for (x in 45 - i / 2 until 55 + i / 2) mask[(15 + i) * 100 + x] = true
        }

        assertEquals(
            NavManeuverCodes.GAODE_STRAIGHT,
            WazeVisualManeuverReader.classifyForegroundMask(100, 100, mask)?.maneuverGaode,
        )
    }

    @Test fun `tiny disconnected noise is rejected`() {
        val mask = BooleanArray(100 * 100)
        repeat(12) { mask[(it * 317) % mask.size] = true }

        assertNull(WazeVisualManeuverReader.classifyForegroundMask(100, 100, mask))
    }

    @Test fun `plain circular notification badge is not mistaken for straight`() {
        val mask = BooleanArray(100 * 100)
        for (y in 5 until 95) for (x in 5 until 95) {
            val dx = x - 50
            val dy = y - 50
            if (dx * dx + dy * dy <= 40 * 40) mask[y * 100 + x] = true
        }

        assertEquals(
            0,
            WazeVisualManeuverReader.classifyForegroundMask(100, 100, mask)?.maneuverGaode,
        )
    }

    @Test fun `white arrow is recovered from inside coloured maneuver disc`() {
        val width = 100
        val pixels = IntArray(width * width) { 0xff202124.toInt() }
        val purple = 0xff7356a8.toInt()
        val white = 0xfff7f7f7.toInt()
        for (y in 10 until 95) {
            for (x in 5 until 95) {
                val dx = x - 50
                val dy = y - 52
                if (dx * dx + dy * dy <= 40 * 40) pixels[y * width + x] = purple
            }
        }
        turnMask(right = true).forEachIndexed { index, arrow ->
            if (arrow) pixels[index] = white
        }

        assertEquals(
            NavManeuverCodes.GAODE_RIGHT,
            WazeVisualManeuverReader.classifyPixels(width, width, pixels)?.maneuverGaode,
        )
    }
}

package com.bydmate.app.navdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WazeVisualManeuverReaderTest {
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

    @Test fun `straight symmetric shape never invents a side`() {
        val mask = BooleanArray(100 * 100)
        for (y in 15 until 92) for (x in 45 until 55) mask[y * 100 + x] = true
        for (i in 0 until 18) {
            for (x in 45 - i / 2 until 55 + i / 2) mask[(15 + i) * 100 + x] = true
        }

        assertEquals(
            0,
            WazeVisualManeuverReader.classifyForegroundMask(100, 100, mask)?.maneuverGaode,
        )
    }

    @Test fun `tiny disconnected noise is rejected`() {
        val mask = BooleanArray(100 * 100)
        repeat(12) { mask[(it * 317) % mask.size] = true }

        assertNull(WazeVisualManeuverReader.classifyForegroundMask(100, 100, mask))
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

package com.bydmate.app.ui.charges

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeSocValidationTest {

    @Test
    fun `blank or partial valid SOC keeps manual kWh fallback available`() {
        assertFalse(validateChargeSoc("", "").hasError)
        assertFalse(validateChargeSoc("20", "").hasError)
        assertFalse(validateChargeSoc("", "80").hasError)
    }

    @Test
    fun `boundary SOC pair is valid and calculable`() {
        val result = validateChargeSoc("0", "100")

        assertFalse(result.hasError)
        assertTrue(result.canCalculateKwh)
        assertEquals(0, result.start)
        assertEquals(100, result.end)
    }

    @Test
    fun `provided SOC outside zero to one hundred is invalid`() {
        assertTrue(validateChargeSoc("-1", "80").startHasError)
        assertTrue(validateChargeSoc("20", "101").endHasError)
        assertTrue(validateChargeSoc("not-a-number", "80").startHasError)
    }

    @Test
    fun `equal or decreasing SOC pair is invalid`() {
        assertTrue(validateChargeSoc("80", "80").orderHasError)
        assertTrue(validateChargeSoc("90", "40").orderHasError)
    }

    @Test
    fun `increasing SOC pair preserves parsed values`() {
        val result = validateChargeSoc(" 25 ", "75")

        assertFalse(result.hasError)
        assertEquals(25, result.start)
        assertEquals(75, result.end)
    }
}

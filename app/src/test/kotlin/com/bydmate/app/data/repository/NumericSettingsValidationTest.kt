package com.bydmate.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NumericSettingsValidationTest {
    @Test fun `numeric parser accepts comma and rejects non finite values`() {
        assertEquals(80.64, "80,64".parseNumericSetting()!!, 0.0001)
        assertNull("NaN".parseNumericSetting())
        assertNull("Infinity".parseNumericSetting())
    }

    @Test fun `battery capacity must stay inside defensive physical range`() {
        assertTrue("80,64".isValidBatteryCapacitySetting())
        assertFalse("0".isValidBatteryCapacitySetting())
        assertFalse("-1".isValidBatteryCapacitySetting())
        assertFalse("251".isValidBatteryCapacitySetting())
        assertFalse("".isValidBatteryCapacitySetting())
    }

    @Test fun `tariff accepts zero but rejects negative and non finite values`() {
        assertTrue("0".isValidTariffSetting())
        assertTrue("1250,50".isValidTariffSetting())
        assertFalse("-0.01".isValidTariffSetting())
        assertFalse("NaN".isValidTariffSetting())
    }

    @Test fun `normalization stores locale independent decimal`() {
        assertEquals("80.64", " 80,64 ".normalizedNumericSetting())
    }
}

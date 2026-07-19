package com.bydmate.app.ui.welcome

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WelcomeValidationTest {
    @Test fun `default Sea Lion settings are valid`() {
        assertTrue(WelcomeUiState().hasValidNumericSettings())
    }

    @Test fun `invalid capacity or selected custom tariff blocks onboarding`() {
        assertFalse(WelcomeUiState(batteryCapacity = "0").hasValidNumericSettings())
        assertFalse(
            WelcomeUiState(tripCostMode = "custom", customTariff = "")
                .hasValidNumericSettings(),
        )
    }

    @Test fun `unused invalid custom tariff does not block standard tariff mode`() {
        assertTrue(
            WelcomeUiState(tripCostMode = "home", customTariff = "")
                .hasValidNumericSettings(),
        )
    }
}

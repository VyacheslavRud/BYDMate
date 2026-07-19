package com.bydmate.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsVehicleDefaultsMigrationTest {
    @Test fun `fresh install receives Sea Lion defaults and migration flag`() {
        val updates = planVehicleDefaultsMigration(
            VehicleDefaultsSnapshot(false, null, null, null),
        )

        assertEquals("80.64", updates[SettingsRepository.KEY_BATTERY_CAPACITY])
        assertEquals("18", updates[SettingsRepository.KEY_CONSUMPTION_GOOD])
        assertEquals("24", updates[SettingsRepository.KEY_CONSUMPTION_BAD])
        assertEquals("true", updates[SettingsRepository.KEY_MIGRATION_SEA_LION_PROFILE_V1])
    }

    @Test fun `legacy defaults including comma formatting are migrated`() {
        val updates = planVehicleDefaultsMigration(
            VehicleDefaultsSnapshot(false, "72,9", "20.0", "30"),
        )

        assertEquals("80.64", updates[SettingsRepository.KEY_BATTERY_CAPACITY])
        assertEquals("18", updates[SettingsRepository.KEY_CONSUMPTION_GOOD])
        assertEquals("24", updates[SettingsRepository.KEY_CONSUMPTION_BAD])
    }

    @Test fun `custom values are preserved independently`() {
        val updates = planVehicleDefaultsMigration(
            VehicleDefaultsSnapshot(false, "79.5", "17", "27"),
        )

        assertFalse(updates.containsKey(SettingsRepository.KEY_BATTERY_CAPACITY))
        assertFalse(updates.containsKey(SettingsRepository.KEY_CONSUMPTION_GOOD))
        assertFalse(updates.containsKey(SettingsRepository.KEY_CONSUMPTION_BAD))
        assertTrue(updates.containsKey(SettingsRepository.KEY_MIGRATION_SEA_LION_PROFILE_V1))
    }

    @Test fun `completed migration never plans another write`() {
        val updates = planVehicleDefaultsMigration(
            VehicleDefaultsSnapshot(true, "72.9", "20", "30"),
        )

        assertTrue(updates.isEmpty())
    }
}

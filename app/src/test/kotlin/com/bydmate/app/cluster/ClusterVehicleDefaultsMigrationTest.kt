package com.bydmate.app.cluster

import com.bydmate.app.data.vehicle.ClusterProjectionPreset
import com.bydmate.app.data.vehicle.VehicleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterVehicleDefaultsMigrationTest {
    private val preset = VehicleProfile.CURRENT.clusterProjectionPreset

    @Test fun `fresh install gets Sea Lion geometry and safe auto-container default`() {
        val migration = planClusterDefaultsMigration(
            ClusterDefaultsSnapshot(false, null, null, null, null, null, null),
        )

        assertEquals(preset, migration.geometryToPersist)
        assertEquals(false, migration.autoContainer)
        assertTrue(migration.markDone)
    }

    @Test fun `complete legacy geometry gets Sea Lion preset`() {
        val migration = planClusterDefaultsMigration(
            ClusterDefaultsSnapshot(false, 100, 100, 50, 50, 100, true),
        )

        assertEquals(preset, migration.geometryToPersist)
        assertNull(migration.autoContainer)
    }

    @Test fun `partial legacy geometry gets Sea Lion preset`() {
        val migration = planClusterDefaultsMigration(
            ClusterDefaultsSnapshot(false, 100, 100, null, null, null, null),
        )

        assertEquals(preset, migration.geometryToPersist)
    }

    @Test fun `partial custom geometry materializes legacy fallbacks`() {
        val migration = planClusterDefaultsMigration(
            ClusterDefaultsSnapshot(false, 32, null, null, null, null, null),
        )

        assertEquals(
            ClusterProjectionPreset(32, 100, 50, 50, 100),
            migration.geometryToPersist,
        )
        assertEquals(false, migration.autoContainer)
    }

    @Test fun `complete custom geometry is preserved`() {
        val migration = planClusterDefaultsMigration(
            ClusterDefaultsSnapshot(false, 40, 92, 100, 6, 90, false),
        )

        assertNull(migration.geometryToPersist)
        assertNull(migration.autoContainer)
    }

    @Test fun `explicit auto-container choices are both preserved`() {
        val enabled = planClusterDefaultsMigration(
            ClusterDefaultsSnapshot(false, null, null, null, null, null, true),
        )
        val disabled = planClusterDefaultsMigration(
            ClusterDefaultsSnapshot(false, null, null, null, null, null, false),
        )

        assertNull(enabled.autoContainer)
        assertNull(disabled.autoContainer)
    }

    @Test fun `completed migration is a no-op`() {
        val migration = planClusterDefaultsMigration(
            ClusterDefaultsSnapshot(true, null, null, null, null, null, null),
        )

        assertNull(migration.geometryToPersist)
        assertNull(migration.autoContainer)
        assertFalse(migration.markDone)
    }
}

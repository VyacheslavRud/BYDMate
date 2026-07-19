package com.bydmate.app.data.charging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeGunStateTest {
    @Test fun `only AC and DC input states are charging`() {
        assertTrue(ChargeGunState.isCharging(2))
        assertTrue(ChargeGunState.isCharging(3))
        assertTrue(ChargeGunState.isCharging(4))
        assertFalse(ChargeGunState.isCharging(0))
        assertFalse(ChargeGunState.isCharging(1))
        assertFalse(ChargeGunState.isCharging(5))
        assertFalse(ChargeGunState.isCharging(null))
    }

    @Test fun `only DC variants are fast charging`() {
        assertFalse(ChargeGunState.isDcCharging(2))
        assertTrue(ChargeGunState.isDcCharging(3))
        assertTrue(ChargeGunState.isDcCharging(4))
        assertFalse(ChargeGunState.isDcCharging(5))
    }

    @Test fun `V2L is external connector but energy export`() {
        assertTrue(ChargeGunState.isKnown(5))
        assertTrue(ChargeGunState.isV2l(5))
        assertTrue(ChargeGunState.isExternalConnectorPresent(5))
        assertFalse(ChargeGunState.isCharging(5))
    }
}

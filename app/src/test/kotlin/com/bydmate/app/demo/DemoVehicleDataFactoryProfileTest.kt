package com.bydmate.app.demo

import com.bydmate.app.data.vehicle.VehicleProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class DemoVehicleDataFactoryProfileTest {
    @Test fun `demo snapshot uses current vehicle nominal capacity`() {
        assertEquals(
            VehicleProfile.CURRENT.nominalBatteryKwh,
            DemoVehicleDataFactory.snapshot(elapsedSeconds = 0).batteryCapacityKwh!!,
            0.001,
        )
    }
}

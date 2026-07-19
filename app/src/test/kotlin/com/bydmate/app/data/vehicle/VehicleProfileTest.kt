package com.bydmate.app.data.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleProfileTest {
    private val profile = VehicleProfile.CURRENT

    @Test fun `current profile is 2025 China 610 Zhijia RWD`() {
        assertEquals("BYD", profile.manufacturer)
        assertEquals("Sea Lion 07 EV", profile.model)
        assertEquals("海狮07 EV智驾版", profile.nativeModelName)
        assertEquals("BYD6486SBEV2", profile.modelCode)
        assertEquals(2025, profile.modelYear)
        assertEquals("China", profile.market)
        assertEquals("610 Zhijia / 610智驾版", profile.trim)
        assertEquals(VehicleProfile.Drivetrain.RWD, profile.drivetrain)
        assertEquals(VehicleProfile.Powertrain.BEV, profile.powertrain)
        assertEquals(1, profile.motorCount)
        assertEquals(VehicleProfile.MotorPosition.REAR, profile.motorPosition)
        assertEquals("TZ200XYT", profile.motorCode)
        assertEquals("rear single motor", profile.motorLayout)
    }

    @Test fun `current profile has only confirmed battery and power values`() {
        assertEquals(80.64, profile.nominalBatteryKwh, 0.001)
        assertNull(profile.usableBatteryKwh)
        assertEquals("LFP Blade", profile.batteryChemistry)
        assertEquals(150, profile.batteryRatedAh)
        assertNull(profile.nominalPackVoltageV)
        assertEquals(800, profile.electricalArchitectureV)
        assertEquals(230, profile.motorPowerKw)
        assertEquals(70, profile.motorRatedPowerKw)
        assertEquals(380, profile.torqueNm)
        assertEquals(610, profile.cltcRangeKm)
        assertEquals(15.2, profile.ratedConsumptionKwhPer100Km, 0.001)
        assertEquals(2_210, profile.curbWeightKg)
        assertEquals(2_585, profile.grossWeightKg)
        assertEquals(4_830, profile.lengthMm)
        assertEquals(1_925, profile.widthMm)
        assertEquals(1_620, profile.heightMm)
        assertEquals(2_930, profile.wheelbaseMm)
        assertEquals(5, profile.seats)
        assertEquals(225, profile.maxSpeedKph)
    }

    @Test fun `current profile has Sea Lion tires assistance and charging standard`() {
        assertEquals("235/50 R19", profile.frontTire)
        assertEquals("255/45 R19", profile.rearTire)
        assertEquals(250, profile.comfortTirePressureKpa)
        assertEquals(290, profile.economyTirePressureKpa)
        assertEquals(210, profile.lowTireWarningKpa)
        assertEquals("GB/T (China)", profile.chargingStandard)
        assertEquals("DiPilot 300", profile.driverAssistanceSystem)
        assertEquals(1, profile.lidarCount)
    }

    @Test fun `UX thresholds and cluster preset match target defaults`() {
        assertEquals(18, profile.consumptionGoodHeuristicKwhPer100Km)
        assertEquals(24, profile.consumptionBadHeuristicKwhPer100Km)
        assertEquals(
            ClusterProjectionPreset(32, 92, 100, 6, 90),
            profile.clusterProjectionPreset,
        )
    }
}

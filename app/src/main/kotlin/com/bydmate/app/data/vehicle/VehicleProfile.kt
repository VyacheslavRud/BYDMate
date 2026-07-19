package com.bydmate.app.data.vehicle

/**
 * Vehicle-specific values that are safe to use as application defaults.
 *
 * Live telemetry always remains authoritative. In particular, [usableBatteryKwh] and
 * [nominalPackVoltageV] intentionally stay nullable: neither value should be inferred from the
 * marketed battery capacity or the 800 V platform name.
 */
data class VehicleProfile(
    val id: String,
    val manufacturer: String,
    val model: String,
    val nativeModelName: String,
    val modelCode: String,
    val modelYear: Int,
    val market: String,
    val trim: String,
    val drivetrain: Drivetrain,
    val powertrain: Powertrain,
    val motorCount: Int,
    val motorPosition: MotorPosition,
    val motorCode: String,
    val motorLayout: String,
    val nominalBatteryKwh: Double,
    val usableBatteryKwh: Double?,
    val batteryChemistry: String,
    val batteryRatedAh: Int,
    val nominalPackVoltageV: Double?,
    val electricalArchitectureV: Int,
    val motorPowerKw: Int,
    val motorRatedPowerKw: Int,
    val torqueNm: Int,
    val cltcRangeKm: Int,
    val ratedConsumptionKwhPer100Km: Double,
    val curbWeightKg: Int,
    val grossWeightKg: Int,
    val lengthMm: Int,
    val widthMm: Int,
    val heightMm: Int,
    val wheelbaseMm: Int,
    val seats: Int,
    val maxSpeedKph: Int,
    val frontTire: String,
    val rearTire: String,
    val comfortTirePressureKpa: Int,
    val economyTirePressureKpa: Int,
    /** BYDMate warning boundary; the car's own TPMS warning remains authoritative. */
    val lowTireWarningKpa: Int,
    val chargingStandard: String,
    val driverAssistanceSystem: String,
    val lidarCount: Int,
    /** UI colour boundary, not a homologation or advertised-consumption value. */
    val consumptionGoodHeuristicKwhPer100Km: Int,
    /** UI colour boundary, not a homologation or advertised-consumption value. */
    val consumptionBadHeuristicKwhPer100Km: Int,
    val clusterProjectionPreset: ClusterProjectionPreset,
) {
    enum class Drivetrain { RWD, AWD, FWD }
    enum class Powertrain { BEV }
    enum class MotorPosition { FRONT, REAR, FRONT_AND_REAR }

    companion object {
        /** The single vehicle profile targeted by this development build. */
        val CURRENT = VehicleProfile(
            id = "byd-sea-lion-07-ev-2025-cn-610-zhijia-rwd",
            manufacturer = "BYD",
            model = "Sea Lion 07 EV",
            nativeModelName = "海狮07 EV智驾版",
            modelCode = "BYD6486SBEV2",
            modelYear = 2025,
            market = "China",
            trim = "610 Zhijia / 610智驾版",
            drivetrain = Drivetrain.RWD,
            powertrain = Powertrain.BEV,
            motorCount = 1,
            motorPosition = MotorPosition.REAR,
            motorCode = "TZ200XYT",
            motorLayout = "rear single motor",
            nominalBatteryKwh = 80.64,
            usableBatteryKwh = null,
            batteryChemistry = "LFP Blade",
            batteryRatedAh = 150,
            nominalPackVoltageV = null,
            electricalArchitectureV = 800,
            motorPowerKw = 230,
            motorRatedPowerKw = 70,
            torqueNm = 380,
            cltcRangeKm = 610,
            ratedConsumptionKwhPer100Km = 15.2,
            curbWeightKg = 2_210,
            grossWeightKg = 2_585,
            lengthMm = 4_830,
            widthMm = 1_925,
            heightMm = 1_620,
            wheelbaseMm = 2_930,
            seats = 5,
            maxSpeedKph = 225,
            frontTire = "235/50 R19",
            rearTire = "255/45 R19",
            comfortTirePressureKpa = 250,
            economyTirePressureKpa = 290,
            lowTireWarningKpa = 210,
            chargingStandard = "GB/T (China)",
            driverAssistanceSystem = "DiPilot 300",
            lidarCount = 1,
            consumptionGoodHeuristicKwhPer100Km = 18,
            consumptionBadHeuristicKwhPer100Km = 24,
            clusterProjectionPreset = ClusterProjectionPreset(
                widthPct = 32,
                heightPct = 92,
                offsetXPct = 100,
                offsetYPct = 6,
                scalePct = 90,
            ),
        )
    }
}

data class ClusterProjectionPreset(
    val widthPct: Int,
    val heightPct: Int,
    val offsetXPct: Int,
    val offsetYPct: Int,
    val scalePct: Int,
)

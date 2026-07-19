package com.bydmate.app.demo

import com.bydmate.app.data.remote.DiParsData
import kotlin.math.sin

/** Pure generator used by TrackingService; it never reads or writes vehicle APIs. */
object DemoVehicleDataFactory {
    fun snapshot(elapsedSeconds: Long): DiParsData {
        val wave = sin(elapsedSeconds / 5.0)
        val speed = (46 + wave * 8).toInt().coerceAtLeast(0)
        // Use a steady integration baseline so odometer/energy never move backwards
        // when the animated speed wave is on its downward phase.
        val distanceKm = elapsedSeconds * 46.0 / 3600.0

        return DiParsData(
            soc = 68,
            speed = speed,
            mileage = 28_431.7 + distanceKm,
            power = 8.6 + wave * 3.2,
            chargeGunState = 0,
            maxBatTemp = 28,
            avgBatTemp = 27,
            minBatTemp = 26,
            chargingStatus = 0,
            batteryCapacityKwh = 82.5,
            totalElecConsumption = 5_184.2 + distanceKm * 0.168,
            voltage12v = 13.6,
            maxCellVoltage = 3.728,
            minCellVoltage = 3.714,
            exteriorTemp = 22,
            gear = 4,
            powerState = 2,
            insideTemp = 23,
            acStatus = 1,
            acTemp = 23,
            fanLevel = 2,
            acCirc = 1,
            doorFL = 0,
            doorFR = 0,
            doorRL = 0,
            doorRR = 0,
            windowFL = 0,
            windowFR = 0,
            windowRL = 0,
            windowRR = 0,
            sunroof = 0,
            trunk = 0,
            hood = 0,
            seatbeltFL = 1,
            lockFL = 2,
            tirePressFL = 251,
            tirePressFR = 250,
            tirePressRL = 253,
            tirePressRR = 252,
            driveMode = 1,
            workMode = 1,
            autoPark = 0,
            rain = 0,
            lightLow = 0,
            drl = 1,
            seatbeltFR = 1,
            occupancyFL = 2,
            occupancyFR = 2,
            occupancyRL = 1,
            occupancyRM = 1,
            occupancyRR = 1,
            lightLevel = 4,
            keyBatteryStatus = 0,
            wiperRelay = 0,
            autoWipers = 1,
            bmsState = 0,
        )
    }
}

package com.bydmate.app.demo

import android.content.Context
import androidx.room.withTransaction
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.entity.TripPointEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class DemoSeedResult(val trips: Int, val charges: Int)

@Singleton
class DemoDataSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val tripDao: TripDao,
    private val tripPointDao: TripPointDao,
    private val chargeDao: ChargeDao,
) {
    suspend fun ensureSeeded(forceRefresh: Boolean = false): DemoSeedResult {
        if (!DemoMode.isEnabled(context)) return DemoSeedResult(0, 0)

        val result = db.withTransaction {
            val demoTrips = tripDao.getAllSnapshot().filter { it.source == DEMO_SOURCE }
            val demoCharges = chargeDao.getAll().first().filter { it.detectionSource == DEMO_SOURCE }
            if (!forceRefresh && demoTrips.isNotEmpty() && demoCharges.isNotEmpty()) {
                return@withTransaction DemoSeedResult(demoTrips.size, demoCharges.size)
            }

            deleteDemoRows(demoTrips, demoCharges)
            seedTrips()
            val charges = seedCharges()
            DemoSeedResult(TRIP_SPECS.size, charges)
        }
        DemoMode.notifyDataChanged()
        return result
    }

    suspend fun clear(): DemoSeedResult {
        val result = db.withTransaction {
            val trips = tripDao.getAllSnapshot().filter { it.source == DEMO_SOURCE }
            val charges = chargeDao.getAll().first().filter { it.detectionSource == DEMO_SOURCE }
            deleteDemoRows(trips, charges)
            DemoSeedResult(trips.size, charges.size)
        }
        DemoMode.notifyDataChanged()
        return result
    }

    private suspend fun deleteDemoRows(trips: List<TripEntity>, charges: List<ChargeEntity>) {
        trips.forEach { trip ->
            tripPointDao.deleteByTripId(trip.id)
            tripDao.deleteById(trip.id)
        }
        charges.forEach { chargeDao.delete(it) }
    }

    private suspend fun seedTrips() {
        val now = System.currentTimeMillis()
        TRIP_SPECS.forEachIndexed { index, spec ->
            val start = now - spec.ageHours * HOUR_MS
            val end = start + spec.durationMinutes * MINUTE_MS
            val id = tripDao.insert(
                TripEntity(
                    startTs = start,
                    endTs = end,
                    distanceKm = spec.distanceKm,
                    kwhConsumed = spec.kwh,
                    kwhPer100km = spec.kwh / spec.distanceKm * 100.0,
                    socStart = spec.socStart,
                    socEnd = spec.socEnd,
                    tempAvgC = spec.exteriorTemp.toDouble(),
                    avgSpeedKmh = spec.distanceKm / (spec.durationMinutes / 60.0),
                    batTempAvg = 27.0,
                    batTempMax = 29.0,
                    batTempMin = 25.0,
                    cost = spec.kwh * 0.25,
                    exteriorTemp = spec.exteriorTemp,
                    source = DEMO_SOURCE,
                )
            )
            if (index == 0) seedLatestRoute(id, start, end)
        }
    }

    private suspend fun seedLatestRoute(tripId: Long, start: Long, end: Long) {
        val route = listOf(
            50.0755 to 14.4378,
            50.0718 to 14.4496,
            50.0669 to 14.4614,
            50.0620 to 14.4748,
            50.0584 to 14.4891,
            50.0547 to 14.5038,
            50.0509 to 14.5184,
            50.0472 to 14.5320,
        )
        val step = (end - start) / (route.size - 1)
        tripPointDao.insertAll(route.mapIndexed { index, (lat, lon) ->
            TripPointEntity(
                tripId = tripId,
                timestamp = start + index * step,
                lat = lat,
                lon = lon,
                speedKmh = if (index == 0 || index == route.lastIndex) 0.0 else 42.0 + index,
            )
        })
    }

    private suspend fun seedCharges(): Int {
        val now = System.currentTimeMillis()
        CHARGE_SPECS.forEach { spec ->
            val start = now - spec.ageHours * HOUR_MS
            val end = start + spec.durationMinutes * MINUTE_MS
            chargeDao.insert(
                ChargeEntity(
                    startTs = start,
                    endTs = end,
                    socStart = spec.socStart,
                    socEnd = spec.socEnd,
                    kwhCharged = spec.kwh,
                    kwhChargedSoc = spec.kwh * 0.96,
                    maxPowerKw = spec.maxPowerKw,
                    type = spec.type,
                    cost = spec.kwh * if (spec.type == "DC") 0.55 else 0.25,
                    lat = 50.0755,
                    lon = 14.4378,
                    batTempAvg = 26.0,
                    batTempMax = 29.0,
                    batTempMin = 23.0,
                    avgPowerKw = spec.kwh / (spec.durationMinutes / 60.0),
                    status = "COMPLETED",
                    cellVoltageMin = 3.702,
                    cellVoltageMax = 3.719,
                    voltage12v = 13.5,
                    exteriorTemp = 20,
                    lifetimeKwhAtStart = 5_184.2 - spec.kwh,
                    lifetimeKwhAtFinish = 5_184.2,
                    gunState = 0,
                    detectionSource = DEMO_SOURCE,
                )
            )
        }
        return CHARGE_SPECS.size
    }

    private data class TripSpec(
        val ageHours: Long,
        val durationMinutes: Long,
        val distanceKm: Double,
        val kwh: Double,
        val socStart: Int,
        val socEnd: Int,
        val exteriorTemp: Int,
    )

    private data class ChargeSpec(
        val ageHours: Long,
        val durationMinutes: Long,
        val kwh: Double,
        val socStart: Int,
        val socEnd: Int,
        val type: String,
        val maxPowerKw: Double,
    )

    companion object {
        const val DEMO_SOURCE = "demo"
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 60L * MINUTE_MS

        private val TRIP_SPECS = listOf(
            TripSpec(2, 28, 14.8, 2.45, 72, 68, 22),
            TripSpec(27, 41, 26.3, 4.52, 81, 74, 19),
            TripSpec(72, 22, 9.7, 1.71, 66, 63, 17),
            TripSpec(144, 55, 38.6, 6.41, 88, 78, 21),
            TripSpec(288, 34, 21.2, 3.82, 59, 53, 16),
            TripSpec(840, 67, 48.9, 8.76, 92, 79, 12),
        )

        private val CHARGE_SPECS = listOf(
            ChargeSpec(20, 210, 31.4, 38, 76, "AC", 11.0),
            ChargeSpec(120, 245, 38.1, 29, 75, "AC", 11.0),
            ChargeSpec(360, 32, 29.7, 34, 70, "DC", 93.0),
            ChargeSpec(960, 190, 27.8, 45, 79, "AC", 11.0),
        )
    }
}

package com.bydmate.app.demo

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.TripEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class DemoDataSeederTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var seeder: DemoDataSeeder

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        DemoMode.setEnabled(context, true)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        seeder = DemoDataSeeder(context, db, db.tripDao(), db.tripPointDao(), db.chargeDao())
    }

    @After
    fun teardown() {
        db.close()
        DemoMode.setEnabled(context, false)
    }

    @Test
    fun `seed and clear affect only demo rows`() = runBlocking {
        db.tripDao().insert(TripEntity(startTs = 1L, source = "live"))
        db.chargeDao().insert(ChargeEntity(startTs = 1L, detectionSource = null))

        val seeded = seeder.ensureSeeded(forceRefresh = true)
        assertEquals(DemoSeedResult(6, 4), seeded)
        assertEquals(7, db.tripDao().getAllSnapshot().size)
        assertEquals(5, db.chargeDao().getAll().first().size)

        val latestDemo = db.tripDao().getAllSnapshot()
            .filter { it.source == DemoDataSeeder.DEMO_SOURCE }
            .maxBy { it.startTs }
        assertTrue(db.tripPointDao().getByTripId(latestDemo.id).isNotEmpty())

        // A normal ensure is idempotent and must not duplicate the history.
        assertEquals(DemoSeedResult(6, 4), seeder.ensureSeeded())
        assertEquals(7, db.tripDao().getAllSnapshot().size)
        assertEquals(5, db.chargeDao().getAll().first().size)

        assertEquals(DemoSeedResult(6, 4), seeder.clear())
        assertEquals(listOf("live"), db.tripDao().getAllSnapshot().map { it.source })
        assertEquals(1, db.chargeDao().getAll().first().size)
    }

    @Test
    fun `vehicle snapshots are realistic and move over time`() {
        val first = DemoVehicleDataFactory.snapshot(0)
        val later = DemoVehicleDataFactory.snapshot(60)

        assertEquals(68, first.soc)
        assertEquals(4, first.gear)
        assertEquals(2, first.powerState)
        assertEquals(0, first.doorFL)
        assertTrue((later.mileage ?: 0.0) > (first.mileage ?: 0.0))
        assertTrue((first.speed ?: 0) in 35..60)
        assertTrue((first.minCellVoltage ?: 0.0) < (first.maxCellVoltage ?: 0.0))
    }
}

package com.bydmate.app.demo

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.local.dao.VehicleWriteLogDao
import com.bydmate.app.data.nativestack.ParsReader
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.SeatChannelStore
import com.bydmate.app.data.vehicle.VehicleApiImpl
import com.bydmate.app.data.vehicle.VehicleWriteError
import com.bydmate.app.data.vehicle.WriteAllowlist
import io.mockk.coVerify
import io.mockk.mockk
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
class DemoVehicleApiTest {
    private lateinit var context: Context
    private val parsReader = mockk<ParsReader>(relaxed = true)
    private val autoservice = mockk<AutoserviceClient>(relaxed = true)
    private val helper = mockk<HelperClient>(relaxed = true)
    private val api = VehicleApiImpl(
        parsReader,
        autoservice,
        helper,
        mockk<WriteAllowlist>(relaxed = true),
        mockk<VehicleWriteLogDao>(relaxed = true),
        mockk<SeatChannelStore>(relaxed = true),
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        DemoMode.setEnabled(context, true)
    }

    @After
    fun teardown() {
        DemoMode.setEnabled(context, false)
    }

    @Test
    fun `demo returns local snapshots and blocks every vehicle write`() = runBlocking {
        assertEquals(68, api.readSnapshot()?.soc)
        assertEquals(97.4f, api.readBatterySnapshot()?.sohPercent)

        val write = api.writeAcOn()
        assertTrue(write.isFailure)
        assertTrue(write.exceptionOrNull() is VehicleWriteError.Unsupported)

        coVerify(exactly = 0) { parsReader.fetch() }
        coVerify(exactly = 0) { autoservice.readBatterySnapshot() }
        coVerify(exactly = 0) { helper.write(any(), any(), any()) }
    }
}

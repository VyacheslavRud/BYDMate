package com.bydmate.app.helper

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class AutoContainerProjectionInfoBinderTest {

    private fun binder(
        serviceResult: Int,
        descriptor: String = "android.os.IAutoContainer",
    ): Pair<IBinder, () -> Int> {
        var calls = 0
        val binder = object : IBinder {
            override fun isBinderAlive() = true
            override fun pingBinder() = true
            override fun getInterfaceDescriptor() = descriptor
            override fun queryLocalInterface(descriptor: String): IInterface? = null
            @Suppress("OVERRIDE_DEPRECATION")
            override fun dump(fd: java.io.FileDescriptor, args: Array<String>?) = Unit
            override fun dumpAsync(fd: java.io.FileDescriptor, args: Array<String>?) = Unit
            override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = Unit
            override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = true

            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                calls += 1
                assertEquals(5, code)
                data.setDataPosition(0)
                data.enforceInterface("android.os.IAutoContainer")
                val out = requireNotNull(reply)
                out.writeNoException()
                out.writeInt(serviceResult)
                out.writeInt(1)
                val parcelStart = out.dataPosition()
                out.writeInt(0)
                out.writeString("cluster_projection")
                out.writeInt(1920)
                out.writeInt(720)
                out.writeStrongBinder(Binder())
                val parcelEnd = out.dataPosition()
                out.setDataPosition(parcelStart)
                out.writeInt(parcelEnd - parcelStart)
                out.setDataPosition(0)
                return true
            }
        }
        return binder to { calls }
    }

    @Test
    fun `direct getter sends no argument and decodes returned surface parcel once`() {
        val (binder, calls) = binder(serviceResult = 0)

        val result = readAutoContainerProjectionInfo(binder)

        assertEquals(1, calls())
        assertEquals("PARCEL_CAPTURED", result.status)
        assertEquals(0, result.serviceResult)
        assertTrue(result.parcelPresent == true)
        assertTrue(result.parcelCaptured)
        assertEquals("cluster_projection", result.name)
        assertEquals(1920, result.width)
        assertEquals(720, result.height)
        assertTrue(result.surfacePresent == true)
    }

    @Test
    fun `non-zero service result never becomes usable even when parcel is decodable`() {
        val (binder, calls) = binder(serviceResult = -1)

        val result = readAutoContainerProjectionInfo(binder)
        val report = buildAutoContainerProjectionInfoProbe(result)

        assertEquals(1, calls())
        assertEquals("SERVICE_NON_ZERO", result.status)
        assertTrue(result.parcelCaptured)
        assertTrue(report.contains("parcelCaptured=true usable=false"))
    }

    @Test
    fun `vendor native endpoint may omit descriptor but still uses the proven AIDL transaction`() {
        val (binder, calls) = binder(serviceResult = 0, descriptor = "")

        val result = readAutoContainerProjectionInfo(
            binder = binder,
            serviceName = "AutoContainerNative",
            allowMissingDescriptor = true,
        )

        assertEquals(1, calls())
        assertEquals("AutoContainerNative", result.serviceName)
        assertEquals("PARCEL_CAPTURED", result.status)
        assertTrue(result.parcelCaptured)
    }

    @Test
    fun `selection prefers first usable native result and preserves fallback evidence`() {
        val native = AutoContainerProjectionInfoResult(
            serviceName = "AutoContainerNative",
            status = "PARCEL_CAPTURED",
            serviceResult = 0,
            parcelCaptured = true,
        )
        val bridge = AutoContainerProjectionInfoResult(
            serviceName = "auto_container",
            descriptor = "android.os.IAutoContainer",
            status = "PARCEL_CAPTURED",
            serviceResult = 0,
            parcelCaptured = true,
        )

        val selected = selectAutoContainerProjectionInfo(listOf(native, bridge))
        val report = buildAutoContainerProjectionInfoProbe(selected, listOf(native, bridge))

        assertEquals("AutoContainerNative", selected.serviceName)
        assertTrue(report.contains("schema=4"))
        assertTrue(report.contains("source=AutoContainerNative descriptor=unpublished"))
        assertTrue(report.contains("[auto_container_attempt_2] source=auto_container"))
    }

    @Test
    fun `fission inventory mirrors vendor transaction 101 without request arguments`() {
        var calls = 0
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                calls += 1
                assertEquals(101, code)
                assertEquals(0, data.dataSize())
                requireNotNull(reply).apply {
                    writeInt(2)
                    writeString("projection_main")
                    writeInt(1920)
                    writeInt(720)
                    writeStrongBinder(Binder())
                    writeString("projection_aux")
                    writeInt(1280)
                    writeInt(480)
                    writeStrongBinder(Binder())
                    setDataPosition(0)
                }
                return true
            }
        }

        val inventory = readFissionProjectionDisplays(binder)
        val report = buildAutoContainerProjectionInfoProbe(
            selected = AutoContainerProjectionInfoResult(status = "SERVICE_UNAVAILABLE"),
            fission = inventory,
        )

        assertEquals(1, calls)
        assertEquals("CAPTURED", inventory.status)
        assertEquals(2, inventory.displays.size)
        assertEquals("projection_aux", inventory.displays[1].name)
        assertTrue(report.contains("[fission_projection_inventory] status=CAPTURED reportedCount=2"))
        assertTrue(report.contains("[fission_projection_2] name=projection_aux width=1280 height=480"))
    }
}

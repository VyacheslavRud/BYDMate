package com.bydmate.app.data.vehicle

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import com.bydmate.app.helper.HelperBinderProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelperClientClusterProbeTest {
    private fun client(status: Int, report: String?, handled: Boolean = true): HelperClientImpl {
        val binder = object : IBinder {
            override fun isBinderAlive() = true
            override fun pingBinder() = true
            override fun getInterfaceDescriptor() = HelperBinderProtocol.DESCRIPTOR
            override fun queryLocalInterface(descriptor: String): IInterface? = null
            @Suppress("OVERRIDE_DEPRECATION")
            override fun dump(fd: java.io.FileDescriptor, args: Array<String>?) = Unit
            override fun dumpAsync(fd: java.io.FileDescriptor, args: Array<String>?) = Unit
            override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = Unit
            override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = true
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (!handled) return false
                assertEquals(HelperBinderProtocol.TX_GET_CLUSTER_SYSTEM_PROBE, code)
                data.setDataPosition(0)
                data.enforceInterface(HelperBinderProtocol.DESCRIPTOR)
                reply!!.writeInt(status)
                reply.writeString(report)
                reply.setDataPosition(0)
                return true
            }
        }
        return object : HelperClientImpl() { override fun resolveBinder(): IBinder = binder }
    }

    @Test fun `client parses successful fixed cluster probe`() = runBlocking {
        assertEquals(
            "schema=1\n[display_manager] exit=0",
            client(0, "schema=1\n[display_manager] exit=0").getClusterSystemProbe(),
        )
    }

    @Test fun `client rejects error empty and unhandled replies`() = runBlocking {
        assertNull(client(-1, "error").getClusterSystemProbe())
        assertNull(client(0, "").getClusterSystemProbe())
        assertNull(client(0, "unused", handled = false).getClusterSystemProbe())
    }
}

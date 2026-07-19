package com.bydmate.app.data.vehicle

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import com.bydmate.app.helper.HelperBinderProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelperClientWazeDeepLinkTest {

    private fun binder(status: Int, capture: (Int, String?) -> Unit): IBinder = object : IBinder {
        override fun isBinderAlive() = true
        override fun pingBinder() = true
        override fun getInterfaceDescriptor() = HelperBinderProtocol.DESCRIPTOR
        override fun queryLocalInterface(descriptor: String): IInterface? = null
        @Suppress("OVERRIDE_DEPRECATION")
        override fun dump(fd: java.io.FileDescriptor, args: Array<String>?) {}
        override fun dumpAsync(fd: java.io.FileDescriptor, args: Array<String>?) {}
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {}
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = true
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.setDataPosition(0)
            data.enforceInterface(HelperBinderProtocol.DESCRIPTOR)
            capture(code, data.readString())
            reply!!.writeInt(status)
            reply.writeInt(0)
            reply.setDataPosition(0)
            return true
        }
    }

    @Test fun `client marshals Waze URI on narrow transaction`() = runBlocking {
        var seenCode = -1
        var seenUri: String? = null
        val uri = "https://waze.com/ul?ll=50%2C14&navigate=yes"
        val client = object : HelperClientImpl() {
            override fun resolveBinder(): IBinder = binder(0) { code, value ->
                seenCode = code
                seenUri = value
            }
        }
        assertTrue(client.launchWazeDeepLink(uri))
        assertEquals(HelperBinderProtocol.TX_LAUNCH_WAZE_DEEP_LINK, seenCode)
        assertEquals(uri, seenUri)
    }

    @Test fun `client maps daemon rejection and absence to false`() = runBlocking {
        val rejected = object : HelperClientImpl() {
            override fun resolveBinder(): IBinder = binder(-1) { _, _ -> }
        }
        val absent = object : HelperClientImpl() {
            override fun resolveBinder(): IBinder? = null
        }
        assertFalse(rejected.launchWazeDeepLink("https://waze.com/ul?ll=50%2C14"))
        assertFalse(absent.launchWazeDeepLink("https://waze.com/ul?ll=50%2C14"))
    }
}

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
class HelperClientSystemDisplaysTest {
    private fun client(writeReply: (Parcel) -> Unit): HelperClientImpl {
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
                assertEquals(HelperBinderProtocol.TX_GET_SYSTEM_DISPLAYS, code)
                data.setDataPosition(0)
                data.enforceInterface(HelperBinderProtocol.DESCRIPTOR)
                writeReply(checkNotNull(reply))
                reply.setDataPosition(0)
                return true
            }
        }
        return object : HelperClientImpl() { override fun resolveBinder(): IBinder = binder }
    }

    @Test fun `client parses protected system display inventory`() = runBlocking {
        val displays = client { reply ->
            reply.writeInt(0)
            reply.writeInt(2)
            writeDisplay(reply, 0, "内置屏幕", 2560, 1600, 240, 2)
            writeDisplay(reply, 2, "fission_bg_XDJAScreenProjection", 1920, 720, 320, 2)
        }.getSystemDisplays()

        assertEquals(
            listOf(
                SystemDisplayInfo(0, "内置屏幕", 2560, 1600, 240, 2),
                SystemDisplayInfo(2, "fission_bg_XDJAScreenProjection", 1920, 720, 320, 2),
            ),
            displays,
        )
    }

    @Test fun `client rejects daemon error and malformed display inventory`() = runBlocking {
        assertNull(client { it.writeInt(-1); it.writeInt(0) }.getSystemDisplays())
        assertNull(client { it.writeInt(0); it.writeInt(0) }.getSystemDisplays())
        assertNull(client { reply ->
            reply.writeInt(0)
            reply.writeInt(1)
            writeDisplay(reply, 2, "projection", 0, 720, 320, 2)
        }.getSystemDisplays())
    }

    private fun writeDisplay(
        reply: Parcel,
        id: Int,
        name: String,
        width: Int,
        height: Int,
        density: Int,
        state: Int,
    ) {
        reply.writeInt(id)
        reply.writeString(name)
        reply.writeInt(width)
        reply.writeInt(height)
        reply.writeInt(density)
        reply.writeInt(state)
    }
}

package com.bydmate.app.hud

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class HudSomeIpBridgeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun installSomeIpPackage() {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply { packageName = "com.ts.car.someip.service" })
    }

    @Test fun `probe negative on clean device`() {
        assertFalse(HudSomeIpBridge.isServicePresent(context.packageManager))
    }

    @Test fun `probe positive when gateway package installed`() {
        installSomeIpPackage()
        assertTrue(HudSomeIpBridge.isServicePresent(context.packageManager))
    }

    @Test fun `calls fail soft when unbound`() {
        val bridge = HudSomeIpBridge(context)
        assertEquals(-1, bridge.fireEvent(HudSomeIpBridge.TOPIC_NAVI, byteArrayOf(1)))
        assertEquals(-1, bridge.startService(HudSomeIpBridge.SERVICE_ID_NAVI))
        assertEquals(-1, bridge.stopService(HudSomeIpBridge.SERVICE_ID_NAVI))
        bridge.unbind()   // must not throw
    }

    @Test fun `invalid payload is rejected before binder delivery`() {
        val bridge = HudSomeIpBridge(context)
        assertEquals(HudSomeIpBridge.RESULT_INVALID_PAYLOAD, bridge.fireEvent(
            HudSomeIpBridge.TOPIC_NAVI,
            byteArrayOf(),
        ))
        assertEquals(HudSomeIpBridge.RESULT_INVALID_PAYLOAD, bridge.fireEvent(
            HudSomeIpBridge.TOPIC_NAVI,
            ByteArray(HudProtobufBuilder.MAX_PAYLOAD_BYTES + 1),
        ))
    }

    @Test fun `bind gives up when gateway never connects`() = runTest {
        val bridge = HudSomeIpBridge(context)
        assertFalse(bridge.bind())   // virtual time: 4 attempts x 15 s pass instantly
        bridge.unbind()
    }

    @Test fun `active service disconnect notifies controller even without frame traffic`() {
        val reasons = mutableListOf<String>()
        val bridge = HudSomeIpBridge(context, reasons::add)
        val binder = acceptingServerBinder()
        val component = ComponentName(context, "SomeIpServer")
        bridge.serverConn.onServiceConnected(component, binder)
        assertTrue(bridge.isConnected())
        assertEquals(0, bridge.startService(HudSomeIpBridge.SERVICE_ID_NAVI))

        bridge.serverConn.onServiceDisconnected(component)

        assertEquals(listOf("service_disconnected"), reasons)
        assertFalse(bridge.isConnected())
        assertEquals(
            HudSomeIpBridge.RESULT_NOT_CONNECTED,
            bridge.fireEvent(HudSomeIpBridge.TOPIC_NAVI, byteArrayOf(1)),
        )
    }

    @Test fun `reconnected gateway registers callback and reopens active service`() {
        val transactionCodes = mutableListOf<Int>()
        val binder = acceptingServerBinder(transactionCodes)
        val bridge = HudSomeIpBridge(context)
        val component = ComponentName(context, "SomeIpServer")
        bridge.serverConn.onServiceConnected(component, binder)
        assertEquals(0, bridge.startService(HudSomeIpBridge.SERVICE_ID_NAVI))
        bridge.serverConn.onServiceDisconnected(component)

        bridge.serverConn.onServiceConnected(component, binder)

        assertEquals(2, transactionCodes.count { it == IBinder.FIRST_CALL_TRANSACTION })
        assertEquals(2, transactionCodes.count { it == IBinder.FIRST_CALL_TRANSACTION + 3 })
    }

    @Test fun `failed callback registration never publishes half initialized binder`() {
        val bridge = HudSomeIpBridge(context)
        val rejectingBinder = object : Binder() {
            override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int,
            ): Boolean = false
        }

        bridge.serverConn.onServiceConnected(
            ComponentName(context, "SomeIpServer"),
            rejectingBinder,
        )

        assertFalse(bridge.isConnected())
        assertEquals(
            HudSomeIpBridge.RESULT_NOT_CONNECTED,
            bridge.startService(HudSomeIpBridge.SERVICE_ID_NAVI),
        )
    }

    @Test fun `callback answers interface transaction with descriptor`() {
        val bridge = HudSomeIpBridge(context)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            assertTrue(bridge.callback.transact(IBinder.INTERFACE_TRANSACTION, data, reply, 0))
            reply.setDataPosition(0)
            assertEquals("ts.car.someip.sdk.ISomeIpCallback", reply.readString())
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    @Test fun `callback event reply carries exception header and status int`() {
        val bridge = HudSomeIpBridge(context)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("ts.car.someip.sdk.ISomeIpCallback")
            data.writeInt(0)   // "no event" flag, mirrors the gateway ping shape
            assertTrue(bridge.callback.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0))
            reply.setDataPosition(0)
            reply.readException()
            assertEquals(4, reply.dataAvail())   // the AIDL int return - absent before the fix
            assertEquals(0, reply.readInt())
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    @Test fun `callback rejects unknown transaction codes`() {
        val bridge = HudSomeIpBridge(context)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            assertFalse(bridge.callback.transact(IBinder.FIRST_CALL_TRANSACTION + 41, data, reply, 0))
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    private fun acceptingServerBinder(codes: MutableList<Int> = mutableListOf()): IBinder =
        object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                codes += code
                reply?.writeNoException()
                reply?.writeInt(0)
                return true
            }
        }
}

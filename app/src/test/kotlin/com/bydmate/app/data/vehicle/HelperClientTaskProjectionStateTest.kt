package com.bydmate.app.data.vehicle

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import com.bydmate.app.helper.HelperBinderProtocol
import com.bydmate.app.navigation.WazeNavigation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelperClientTaskProjectionStateTest {

    private fun binder(
        replyValues: IntArray,
        handled: Boolean = true,
        capture: (Int, String?) -> Unit = { _, _ -> },
    ): IBinder = object : IBinder {
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
            if (!handled) return false
            data.setDataPosition(0)
            data.enforceInterface(HelperBinderProtocol.DESCRIPTOR)
            capture(code, data.readString())
            replyValues.forEach(reply!!::writeInt)
            reply.setDataPosition(0)
            return true
        }
    }

    private fun clientWith(binder: IBinder?): HelperClientImpl = object : HelperClientImpl() {
        override fun resolveBinder(): IBinder? = binder
    }

    @Test fun `client marshals exact Waze package and parses all task fields`() = runBlocking {
        var seenCode = -1
        var seenPackage: String? = null
        val client = clientWith(binder(intArrayOf(0, 57, 4, 5)) { code, pkg ->
            seenCode = code
            seenPackage = pkg
        })

        assertEquals(
            TaskProjectionQueryResult.Found(
                TaskProjectionState(taskId = 57, displayId = 4, windowingMode = 5),
            ),
            client.getTaskProjectionState(WazeNavigation.PACKAGE_NAME),
        )
        assertEquals(HelperBinderProtocol.TX_GET_TASK_PROJECTION_STATE, seenCode)
        assertEquals(WazeNavigation.PACKAGE_NAME, seenPackage)
    }

    @Test fun `client accepts Waze on main display`() = runBlocking {
        val state = clientWith(binder(intArrayOf(0, 9, 0, 1)))
            .getTaskProjectionState(WazeNavigation.PACKAGE_NAME)
        assertEquals(TaskProjectionQueryResult.Found(TaskProjectionState(9, 0, 1)), state)
    }

    @Test fun `client preserves authoritative Waze not-running result`() = runBlocking {
        val state = clientWith(
            binder(intArrayOf(HelperBinderProtocol.TASK_PROJECTION_NOT_RUNNING, -1, -1, -1)),
        ).getTaskProjectionState(WazeNavigation.PACKAGE_NAME)

        assertEquals(TaskProjectionQueryResult.NotRunning, state)
    }

    @Test fun `client rejects non Waze before touching privileged binder`() = runBlocking {
        var touched = false
        val client = clientWith(binder(intArrayOf(0, 1, 2, 5)) { _, _ -> touched = true })

        assertEquals(
            TaskProjectionQueryResult.Unavailable,
            client.getTaskProjectionState("com.google.android.apps.maps"),
        )
        assertTrue(!touched)
    }

    @Test fun `client maps daemon error malformed state short reply and old daemon to unavailable`() = runBlocking {
        val pkg = WazeNavigation.PACKAGE_NAME
        val unavailable = TaskProjectionQueryResult.Unavailable
        assertEquals(unavailable, clientWith(binder(intArrayOf(-1, -1, -1, -1))).getTaskProjectionState(pkg))
        assertEquals(unavailable, clientWith(binder(intArrayOf(0, 0, 4, 5))).getTaskProjectionState(pkg))
        assertEquals(unavailable, clientWith(binder(intArrayOf(0, 57, -1, 5))).getTaskProjectionState(pkg))
        assertEquals(unavailable, clientWith(binder(intArrayOf(0, 57, 4, 0))).getTaskProjectionState(pkg))
        assertEquals(unavailable, clientWith(binder(intArrayOf(1, 57, 4, 5))).getTaskProjectionState(pkg))
        assertEquals(unavailable, clientWith(binder(intArrayOf(0, 57))).getTaskProjectionState(pkg))
        assertEquals(unavailable, clientWith(binder(intArrayOf(), handled = false)).getTaskProjectionState(pkg))
        assertEquals(unavailable, clientWith(null).getTaskProjectionState(pkg))
    }
}

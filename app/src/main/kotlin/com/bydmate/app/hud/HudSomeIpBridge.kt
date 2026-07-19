package com.bydmate.app.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.delay

/** Anything that can push a HUD frame; HudPushLoop depends on this, tests fake it. */
interface HudEventSink {
    fun fireEvent(topic: Long, payload: ByteArray): Int
}

/** Raw-Binder client of the DiLink SOME/IP gateway (no SDK on the head unit).
 *  Transaction map (donor SomeIpBridge, field-tested): FIRST_CALL_TRANSACTION+0
 *  registerCallback, +3 startService, +4 stopService, +5 fireEvent.
 *
 *  Lifecycle: [bound] mirrors bindService() bookkeeping (must be balanced by
 *  unbindService), [serverBinder] is the LIVE connection - null between a gateway
 *  crash and reconnect. On reconnect the callback is re-registered and the active service
 *  re-opened; an established-channel disconnect also notifies HudController immediately, so a
 *  route with no frame traffic cannot leave runtime status falsely ON. */
class HudSomeIpBridge(
    private val ctx: Context,
    private val onConnectionLost: (String) -> Unit = {},
) : HudEventSink {

    companion object {
        private const val TAG = "HudSomeIpBridge"
        private const val PKG = "com.ts.car.someip.service"
        private const val SERVER_ACTION = "com.ts.car.someip.SomeIpServerService"
        private const val DESC = "ts.car.someip.sdk.ISomeIpServerInterface"
        private const val CB_DESC = "ts.car.someip.sdk.ISomeIpCallback"

        private const val TX_REGISTER_CB = IBinder.FIRST_CALL_TRANSACTION
        private const val TX_START_SERVICE = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val TX_STOP_SERVICE = IBinder.FIRST_CALL_TRANSACTION + 4
        private const val TX_FIRE_EVENT = IBinder.FIRST_CALL_TRANSACTION + 5

        const val TOPIC_NAVI = 0x4010a00018001L
        const val SERVICE_ID_NAVI = 0xB010A00010000L
        internal const val RESULT_NOT_CONNECTED = -1
        internal const val RESULT_TRANSPORT_ERROR = -2
        internal const val RESULT_INVALID_PAYLOAD = -3
        internal const val RESULT_LOCAL_ERROR = -4

        /** Cheap capability probe - MUST run before any binding or helper-daemon work:
         *  cars without the SOME/IP gateway (no factory HUD) take this exit (Codex fix 1). */
        fun isServicePresent(pm: PackageManager): Boolean =
            runCatching { pm.getPackageInfo(PKG, 0) }.isSuccess
    }

    @Volatile private var bound = false
    @Volatile private var serverBinder: IBinder? = null
    @Volatile private var bindConnectionRejected = false

    /** Service id to re-open when the framework reconnects after a gateway crash. */
    @Volatile private var activeServiceId: Long? = null

    // The gateway pings callbacks; the reply must follow the AIDL stub contract or the
    // gateway drops our registration (donor SomeIpBridge shape): INTERFACE_TRANSACTION
    // answers with the descriptor, the event transaction replies writeNoException()+
    // writeInt(0), unknown codes fall through to super. Incoming event payloads are
    // intentionally not read - we only push frames, never consume them.
    internal val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
            when (code) {
                IBinder.INTERFACE_TRANSACTION -> {
                    reply?.writeString(CB_DESC)
                    true
                }
                IBinder.FIRST_CALL_TRANSACTION -> {   // gateway event push (donor code 1)
                    data.enforceInterface(CB_DESC)
                    reply?.writeNoException()
                    reply?.writeInt(0)
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
    }

    internal val serverConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            // Do not publish the binder until callback registration has completed. bind() polls
            // serverBinder and must never return success for a half-initialized gateway channel.
            val registerRc = registerCallback(service)
            if (registerRc < 0) {
                serverBinder = null
                bindConnectionRejected = true
                Log.e(TAG, "server connected but callback registration failed rc=$registerRc")
                if (activeServiceId != null) onConnectionLost("callback_registration_failed")
                return
            }
            serverBinder = service
            bindConnectionRejected = false
            // Reconnect path: the gateway lost both registration and opened services when it died.
            activeServiceId?.let { id ->
                val rc = transact(service, TX_START_SERVICE) { it.writeLong(id) }
                Log.i(TAG, "re-startService(0x${id.toString(16)}) rc=$rc")
                if (rc < 0) {
                    markTransportLost(service, "service_restart_failed")
                    return
                }
            }
            Log.i(TAG, "server connected $name")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            val wasActive = activeServiceId != null
            serverBinder = null   // bound stays true: BIND_AUTO_CREATE may reconnect
            bindConnectionRejected = true
            Log.w(TAG, "server disconnected $name")
            // With no active route there are no fireEvent failures to wake HudController, leaving
            // status falsely ON forever. Notify it immediately once a service had been opened.
            if (wasActive) onConnectionLost("service_disconnected")
        }

        override fun onBindingDied(name: ComponentName) {
            Log.w(TAG, "binding died $name")
            val wasActive = activeServiceId != null
            unbindInternal(clearActiveService = false)
            bindConnectionRejected = true
            if (wasActive) onConnectionLost("binding_died")
        }

        override fun onNullBinding(name: ComponentName) {
            Log.e(TAG, "null binding $name")
            val wasActive = activeServiceId != null
            unbindInternal(clearActiveService = false)
            bindConnectionRejected = true
            if (wasActive) onConnectionLost("null_binding")
        }
    }

    /** Donor retry/backoff: 0/1/3/7 s between attempts, up to 15 s connect wait each.
     *  Suspends only on plain delay(), so cancellation aborts promptly; the caller
     *  must unbind() on cancellation (HudController does). */
    suspend fun bind(): Boolean {
        val backoffMs = longArrayOf(0, 1000, 3000, 7000)
        for (attempt in backoffMs.indices) {
            delay(backoffMs[attempt])
            unbind()
            bindConnectionRejected = false
            val intent = Intent(SERVER_ACTION).setPackage(PKG)
            bound = runCatching { ctx.bindService(intent, serverConn, Context.BIND_AUTO_CREATE) }
                .getOrDefault(false)
            Log.i(TAG, "bind server rc=$bound attempt=$attempt")
            if (!bound) continue
            var tries = 0
            while (serverBinder == null && !bindConnectionRejected && tries++ < 75) delay(200)
            if (serverBinder != null) return true
            Log.w(
                TAG,
                if (bindConnectionRejected) {
                    "server bind rejected attempt=$attempt"
                } else {
                    "server bind timeout attempt=$attempt"
                },
            )
        }
        Log.e(TAG, "server bind failed after ${backoffMs.size} attempts")
        return false
    }

    fun unbind() {
        unbindInternal(clearActiveService = true)
    }

    /** True only while a callback-registered remote binder is currently published. Android can
     * keep bindService bookkeeping alive across onServiceDisconnected, which is not transport
     * liveness and must not make watchdog diagnostics report ON. */
    fun isConnected(): Boolean = serverBinder?.let { binder ->
        runCatching { binder.isBinderAlive && binder.pingBinder() }.getOrDefault(false)
    } == true

    private fun unbindInternal(clearActiveService: Boolean) {
        if (bound) runCatching { ctx.unbindService(serverConn) }
        bound = false
        serverBinder = null
        bindConnectionRejected = false
        if (clearActiveService) activeServiceId = null
    }

    fun startService(serviceId: Long): Int {
        activeServiceId = serviceId
        val binder = serverBinder ?: return RESULT_NOT_CONNECTED
        val rc = transact(binder, TX_START_SERVICE) { it.writeLong(serviceId) }
        Log.i(TAG, "startService(0x${serviceId.toString(16)}) rc=$rc")
        return rc
    }

    fun stopService(serviceId: Long): Int {
        activeServiceId = null
        val binder = serverBinder ?: return RESULT_NOT_CONNECTED
        val rc = transact(binder, TX_STOP_SERVICE) { it.writeLong(serviceId) }
        Log.i(TAG, "stopService(0x${serviceId.toString(16)}) rc=$rc")
        return rc
    }

    override fun fireEvent(topic: Long, payload: ByteArray): Int {
        if (payload.isEmpty() || payload.size > HudProtobufBuilder.MAX_PAYLOAD_BYTES) {
            Log.e(TAG, "fireEvent rejected invalid payload size=${payload.size}")
            return RESULT_INVALID_PAYLOAD
        }
        val binder = serverBinder ?: return RESULT_NOT_CONNECTED
        return transact(binder, TX_FIRE_EVENT) {
            it.writeInt(1)
            it.writeLong(topic)
            it.writeLong(0L)
            it.writeInt(payload.size)
            it.writeByteArray(payload)
        }
    }

    private fun registerCallback(binder: IBinder): Int {
        val rc = transact(binder, TX_REGISTER_CB) { it.writeStrongBinder(callback) }
        Log.i(TAG, "registerCallback rc=$rc")
        return rc
    }

    private inline fun transact(binder: IBinder, code: Int, write: (Parcel) -> Unit): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESC)
            write(data)
            if (!binder.transact(code, data, reply, 0)) {
                Log.e(TAG, "transact($code) rejected by remote binder")
                return RESULT_TRANSPORT_ERROR
            }
            reply.readException()
            reply.readInt()
        } catch (t: Throwable) {
            if (t is RemoteException) {
                val reason = if (t is DeadObjectException) "dead_object" else "remote_exception"
                markTransportLost(binder, reason)
            }
            Log.e(TAG, "transact($code) failed: ${t.message}")
            RESULT_TRANSPORT_ERROR
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun markTransportLost(failedBinder: IBinder, reason: String) {
        // Only the first failure for the currently published binder owns the transition. This
        // prevents a burst of loop transactions from scheduling overlapping reconnects.
        if (serverBinder !== failedBinder) return
        serverBinder = null
        if (activeServiceId != null) onConnectionLost(reason)
    }
}

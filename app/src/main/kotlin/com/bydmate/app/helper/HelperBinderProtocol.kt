package com.bydmate.app.helper

import android.os.IBinder

/**
 * Wire contract shared by the in-app binder client (HelperClientImpl) and the
 * shell-uid daemon (HelperDaemon). The daemon registers under SERVICE_NAME via
 * ServiceManager.addService; the app reaches it with ServiceManager.getService +
 * IBinder.transact.
 *
 * Parcel layout (after data.writeInterfaceToken(DESCRIPTOR) on the request):
 *   TX_PING  : request (no args)                       -> reply: writeInt(status=0)
 *   TX_READ  : writeInt(tx), writeInt(dev), writeInt(fid)
 *                                                        -> reply: writeInt(status), writeInt(value)
 *   TX_WRITE : writeInt(dev), writeInt(fid), writeInt(value)
 *                                                        -> reply: writeInt(status), writeInt(value)
 *   TX_LIST_DISPLAYS : request (no args)
 *       -> reply: writeInt(status), writeInt(count),
 *                 then count * { writeInt(id), writeString(name),
 *                                writeInt(width), writeInt(height), writeInt(densityDpi) }
 *   TX_GET_INSTRUMENT_FEATURE : writeInt(featureId)
 *       -> reply: writeInt(status), writeInt(value)   // status 0 = value valid, <0 = no data/error
 *
 * status/value carry the raw autoservice transact result (see HelperDaemon).
 */
object HelperBinderProtocol {
    const val SERVICE_NAME = "bydmate_helper"
    const val PROCESS_NAME = "bydmate_helper"   // app_process --nice-name + ps lookup
    const val DESCRIPTOR = "com.bydmate.app.helper.IHelper"

    const val TX_PING = IBinder.FIRST_CALL_TRANSACTION       // 1
    const val TX_READ = IBinder.FIRST_CALL_TRANSACTION + 1   // 2
    const val TX_WRITE = IBinder.FIRST_CALL_TRANSACTION + 2  // 3
    const val TX_LIST_DISPLAYS = IBinder.FIRST_CALL_TRANSACTION + 3            // 4
    const val TX_GET_INSTRUMENT_FEATURE = IBinder.FIRST_CALL_TRANSACTION + 4   // 5
}

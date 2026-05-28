@file:JvmName("HelperDaemon")
package com.bydmate.app.helper

import android.os.IBinder
import android.os.Parcel
import kotlin.system.exitProcess

/**
 * Phase 2 helper daemon — single-shot port of Phase 2b's WriteProbe.java.
 *
 * Accepts the same CLI as WriteProbe: <tx> <dev> <fid> [value], performs one
 * Binder transact against the system autoservice, prints the result, exits.
 *
 * Long-lived loopback-server mode lands in B.3 (replaces this main()).
 *
 * Spawned via:
 *   CLASSPATH=/data/local/tmp/helper.dex app_process /system/bin \
 *     --nice-name=bydmate_helper com.bydmate.app.helper.HelperDaemon <tx> <dev> <fid> [value]
 */
fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println("usage: HelperDaemon <tx> <dev> <fid> [value]")
        exitProcess(2)
    }
    val tx = args[0].toInt()
    val dev = args[1].toInt()
    val fid = args[2].toInt()
    val value = args.getOrNull(3)?.toInt() ?: 0

    val smCls = Class.forName("android.os.ServiceManager")
    val svc = smCls.getMethod("getService", String::class.java)
        .invoke(null, "autoservice") as? IBinder
        ?: run { System.err.println("ERR autoservice not found"); exitProcess(3) }

    val data = Parcel.obtain()
    val reply = Parcel.obtain()
    try {
        data.writeInterfaceToken(svc.interfaceDescriptor ?: "")
        data.writeInt(dev)
        data.writeInt(fid)
        if (tx == 6) data.writeInt(value)
        val ok = svc.transact(tx, data, reply, 0)
        val avail = reply.dataAvail()
        var status = -999
        var retInt = -999
        if (avail >= 4) status = reply.readInt()
        if (avail >= 8) retInt = reply.readInt()
        println("RESULT tx=$tx dev=$dev fid=$fid ok=$ok data_avail=$avail status=$status value=$retInt")
    } finally {
        data.recycle()
        reply.recycle()
    }
}

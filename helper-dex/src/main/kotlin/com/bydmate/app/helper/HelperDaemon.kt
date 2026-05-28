@file:JvmName("HelperDaemon")
package com.bydmate.app.helper

import android.os.IBinder
import android.os.Parcel
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.system.exitProcess

/**
 * Phase 2 helper — long-lived in-vehicle daemon.
 *
 * Binds 127.0.0.1:PORT exclusively (single-owner: second spawn fails to bind
 * and exits cleanly with status 1, leaving the first owner intact).
 *
 * Spawned via:
 *   CLASSPATH=/data/local/tmp/helper.dex app_process /system/bin \
 *     --nice-name=bydmate_helper com.bydmate.app.helper.HelperDaemon [port]
 *
 * Protocol: JSON-line over TCP loopback. See HelperRequest / HelperResponse
 * in app/src/main/kotlin/com/bydmate/app/data/vehicle/HelperProtocol.kt
 * (kept duplicated here at the wire level to keep the daemon's classpath
 * minimal — no app deps allowed inside the dex bundle).
 */
fun main(args: Array<String>) {
    val port = args.getOrNull(0)?.toIntOrNull() ?: 8765
    val server = try {
        ServerSocket(port, /* backlog */ 4, InetAddress.getByName("127.0.0.1"))
    } catch (e: Exception) {
        System.err.println("ERR bind 127.0.0.1:$port - already running? ${e.message}")
        exitProcess(1)
    }
    System.out.println("READY 127.0.0.1:$port pid=${android.os.Process.myPid()}")
    System.out.flush()

    val smCls = Class.forName("android.os.ServiceManager")
    val svc = smCls.getMethod("getService", String::class.java)
        .invoke(null, "autoservice") as? IBinder
        ?: run { System.err.println("ERR autoservice not found"); exitProcess(3) }
    val iface = svc.interfaceDescriptor ?: ""

    while (true) {
        val client: Socket = try { server.accept() } catch (_: Exception) { continue }
        Thread {
            try { handle(client, svc, iface) } finally { runCatching { client.close() } }
        }.start()
    }
}

private fun handle(client: Socket, svc: IBinder, iface: String) {
    val reader = client.getInputStream().bufferedReader()
    val writer = client.getOutputStream().bufferedWriter()
    while (true) {
        val line = reader.readLine() ?: return
        val started = System.currentTimeMillis()
        val rsp = runCatching { dispatch(line, svc, iface) }
            .getOrElse { t -> errorJson(t) }
        val elapsed = (System.currentTimeMillis() - started).toInt()
        writer.write(rsp.replaceFirst("\"ms\":0", "\"ms\":$elapsed"))
        writer.flush()
    }
}

private fun dispatch(line: String, svc: IBinder, iface: String): String {
    val req = JSONObject(line)
    val op = req.getString("op")
    if (op == "ping") return """{"status":0,"value":0,"ms":0}""" + "\n"

    val tx = req.optInt("tx", 5)
    val dev = req.getInt("dev")
    val fid = req.getInt("fid")
    val value = req.optLong("value", 0)

    val data = Parcel.obtain()
    val reply = Parcel.obtain()
    try {
        data.writeInterfaceToken(iface)
        data.writeInt(dev)
        data.writeInt(fid)
        if (tx == 6) data.writeInt(value.toInt())
        svc.transact(tx, data, reply, 0)
        val avail = reply.dataAvail()
        val status = if (avail >= 4) reply.readInt() else -999
        val retInt = if (avail >= 8) reply.readInt() else 0
        return """{"status":$status,"value":$retInt,"ms":0}""" + "\n"
    } finally {
        data.recycle()
        reply.recycle()
    }
}

private fun errorJson(t: Throwable): String {
    val msg = (t.message ?: "").replace("\"", "\\\"")
    return """{"status":-1,"value":0,"ms":0,"error":"${t.javaClass.simpleName}: $msg"}""" + "\n"
}

package com.bydmate.app.data.vehicle

import org.json.JSONObject

/**
 * Wire protocol for the helper daemon. JSON-line over 127.0.0.1:8765.
 * One request per line, one response per line. Both sides flush after \n.
 *
 * Examples:
 *   {"op":"read","tx":5,"dev":1014,"fid":1246777400,"value":0}\n
 *   {"op":"write","tx":6,"dev":1000,"fid":501219368,"value":23}\n
 *   {"op":"ping","tx":5,"dev":0,"fid":0,"value":0}\n
 */
data class HelperRequest(
    val op: String,        // "read" | "write" | "ping"
    val tx: Int = 5,       // 5=getInt, 6=setInt, 7=getFloat
    val dev: Int = 0,
    val fid: Int = 0,
    val value: Long = 0,
) {
    fun toJsonLine(): String = JSONObject().apply {
        put("op", op); put("tx", tx); put("dev", dev); put("fid", fid); put("value", value)
    }.toString() + "\n"

    companion object {
        fun fromJsonLine(line: String): HelperRequest {
            val o = JSONObject(line)
            return HelperRequest(
                op = o.getString("op"),
                tx = o.optInt("tx", 5),
                dev = o.optInt("dev", 0),
                fid = o.optInt("fid", 0),
                value = o.optLong("value", 0),
            )
        }
    }
}

/**
 * status: 0 = ok, negative = error (Binder failure, SecurityException, etc.).
 * ms: round-trip time on the daemon side (set by server).
 * error: human-readable error tag set on negative status.
 */
data class HelperResponse(
    val status: Int,
    val value: Long = 0,
    val ms: Int = 0,
    val error: String? = null,
) {
    fun toJsonLine(): String = JSONObject().apply {
        put("status", status); put("value", value); put("ms", ms)
        if (error != null) put("error", error)
    }.toString() + "\n"

    companion object {
        fun fromJsonLine(line: String): HelperResponse {
            val o = JSONObject(line)
            return HelperResponse(
                status = o.getInt("status"),
                value = o.optLong("value", 0),
                ms = o.optInt("ms", 0),
                error = if (o.has("error")) o.getString("error") else null,
            )
        }
    }
}

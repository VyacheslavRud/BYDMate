package com.bydmate.app.hud

import java.io.ByteArrayOutputStream

/** Hand-rolled protobuf encoder for the BYD HUD frame (discope reference, donor stage 6).
 *  Field ORDER inside the inner message is significant for the HUD firmware:
 *  f2 -> f6 -> f7 -> f8 -> f9 -> f10 -> f11 -> f16 -> f26 -> f28 -> f33.
 *  Never emit f3/f4/f12/f17/f18/f21..f25/f30/f31 (verified to glitch the HUD).
 *  Outer wrapper: 0x0A + varint(len) + inner bytes. */
object HudProtobufBuilder {

    const val MAX_PAYLOAD_BYTES = 65536
    const val MAX_ROAD_CHARS = 200
    const val MAX_ETA_CHARS = 16
    const val MAX_SPEED_LIMIT = 250

    /** GAODE maneuver -> f28 reference arrow: 0=unknown, 3=left, 2=right, 9=uturn. */
    fun gaodeToF28(gaode: Int): Int = when (gaode) {
        0 -> 0
        1, 3, 7 -> 3
        2, 4, 8 -> 2
        9, 10 -> 9
        else -> 1
    }

    fun buildFrame(
        maneuverGaode: Int,
        distanceMeters: Int,
        road: String,
        etaString: String?,
        totalDistMeters: Int,
        speedLimit: Int,
        maneuverIconPng: ByteArray?,
        speedSignPng: ByteArray?,
    ): ByteArray = buildFrameWithRawF28(
        rawF28 = maneuverGaode.takeIf { it > 0 }?.let(::gaodeToF28),
        distanceMeters = distanceMeters,
        road = road,
        etaString = etaString,
        totalDistMeters = totalDistMeters,
        speedLimit = speedLimit,
        maneuverIconPng = maneuverIconPng,
        speedSignPng = speedSignPng,
    )

    /**
     * Dev-only native renderer calibration. The frame deliberately omits f8 PNG so the observed
     * arrow can only come from the Sea Lion firmware's interpretation of raw f28.
     */
    fun buildHudLabFrame(rawF28: Int): ByteArray {
        require(rawF28 in setOf(1, 2, 3, 9)) { "unsupported HUD Lab f28=$rawF28" }
        return buildFrameWithRawF28(
            rawF28 = rawF28,
            distanceMeters = 100,
            road = "HUD LAB f28=$rawF28",
            etaString = null,
            totalDistMeters = 0,
            speedLimit = 0,
            maneuverIconPng = null,
            speedSignPng = null,
        )
    }

    private fun buildFrameWithRawF28(
        rawF28: Int?,
        distanceMeters: Int,
        road: String,
        etaString: String?,
        totalDistMeters: Int,
        speedLimit: Int,
        maneuverIconPng: ByteArray?,
        speedSignPng: ByteArray?,
    ): ByteArray {
        val inner = ByteArrayOutputStream()
        // f2 is the constant 2 in every reference guidance frame (donor stage 6,
        // 1779/1779 discope events); only the clear frame carries a counter here.
        writeVarintField(inner, 2, 2L)
        writeVarintField(inner, 6, if (speedSignPng != null) 6L else 1L)
        if (speedSignPng != null) writeBytesField(inner, 7, speedSignPng)
        if (maneuverIconPng != null) writeBytesField(inner, 8, maneuverIconPng)
        writeVarintField(inner, 9, distanceMeters.toLong())
        if (road.isNotEmpty()) writeBytesField(inner, 10, road.toByteArray(Charsets.UTF_8))
        if (speedLimit > 0) writeVarintField(inner, 11, speedLimit.toLong())
        writeVarintField(inner, 16, 2L)
        if (etaString != null) writeBytesField(inner, 26, etaString.toByteArray(Charsets.UTF_8))
        // Do not manufacture f28=1 for an unknown Waze maneuver. On the Sea Lion 07 that
        // fallback is rendered as a LEFT arrow, which is actively misleading on right turns.
        // The route card remains visible and a later parsed A11Y/notification update adds f28.
        rawF28?.let { writeVarintField(inner, 28, it.toLong()) }
        writeFixed64Field(inner, 33, progress(distanceMeters, totalDistMeters).toRawBits())
        return wrap(inner.toByteArray())
    }

    /** Bounded frame builder. The optional speed-sign PNG is dropped first. A corrupt/foreign
     * maneuver asset larger than Binder's safe payload is dropped only as a final fallback; f28
     * still carries the reference direction, which is safer than rejecting every HUD frame. */
    fun buildFrameSafe(
        maneuverGaode: Int,
        distanceMeters: Int,
        road: String,
        etaString: String?,
        totalDistMeters: Int,
        speedLimit: Int,
        maneuverIconPng: ByteArray?,
        speedSignPng: ByteArray?,
    ): ByteArray {
        val safeRoad = road.take(MAX_ROAD_CHARS)
        val safeEta = etaString?.trim()?.take(MAX_ETA_CHARS)?.takeIf { it.isNotEmpty() }
        val safeDistance = distanceMeters.coerceAtLeast(0)
        val safeTotalDistance = totalDistMeters.coerceAtLeast(0)
        val safeSpeedLimit = speedLimit.coerceIn(0, MAX_SPEED_LIMIT)
        val full = buildFrame(maneuverGaode, safeDistance, safeRoad, safeEta,
            safeTotalDistance, safeSpeedLimit, maneuverIconPng, speedSignPng)
        if (full.size <= MAX_PAYLOAD_BYTES) return full
        val withoutSign = if (speedSignPng != null) {
            buildFrame(maneuverGaode, safeDistance, safeRoad, safeEta,
                safeTotalDistance, safeSpeedLimit, maneuverIconPng, speedSignPng = null)
        } else {
            full
        }
        if (withoutSign.size <= MAX_PAYLOAD_BYTES) return withoutSign
        return buildFrame(maneuverGaode, safeDistance, safeRoad, safeEta,
            safeTotalDistance, safeSpeedLimit, maneuverIconPng = null, speedSignPng = null)
    }

    /** Clear frame: render class 255 + f16=1 wipes the HUD navigation area. */
    fun buildClearFrame(counter: Int): ByteArray {
        val inner = ByteArrayOutputStream()
        writeVarintField(inner, 2, counter.toLong())
        writeVarintField(inner, 6, 255L)
        writeVarintField(inner, 16, 1L)
        return wrap(inner.toByteArray())
    }

    private fun progress(distanceMeters: Int, totalDistMeters: Int): Double {
        if (totalDistMeters <= 0) return 0.0
        return (1.0 - distanceMeters.toDouble() / totalDistMeters).coerceIn(0.0, 1.0)
    }

    private fun wrap(inner: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(inner.size + 6)
        out.write(0x0A)
        writeVarint(out, inner.size.toLong())
        out.write(inner)
        return out.toByteArray()
    }

    private fun writeVarintField(out: ByteArrayOutputStream, fieldNo: Int, value: Long) {
        writeVarint(out, (fieldNo.toLong() shl 3) or 0L)
        writeVarint(out, value)
    }

    private fun writeBytesField(out: ByteArrayOutputStream, fieldNo: Int, bytes: ByteArray) {
        writeVarint(out, (fieldNo.toLong() shl 3) or 2L)
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun writeFixed64Field(out: ByteArrayOutputStream, fieldNo: Int, bits: Long) {
        writeVarint(out, (fieldNo.toLong() shl 3) or 1L)
        repeat(8) { i -> out.write(((bits ushr (8 * i)) and 0xFF).toInt()) }
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if (v and 0x7F.inv().toLong() == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }
}

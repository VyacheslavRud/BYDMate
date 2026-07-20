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

    /** GAODE maneuver -> donor f28 maneuver metadata. It is not the f8 PNG arrow itself. */
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

    /**
     * Reproduces the live maneuver part of a guidance frame for parked calibration: the exact
     * bundled donor icon in f8 plus the matching donor metadata in f28. Speed-sign f7 is omitted
     * so only the maneuver path is under test.
     */
    fun buildHudLabLiveFrame(gaodeCode: Int, maneuverIconPng: ByteArray): ByteArray {
        require(gaodeCode in setOf(1, 2, 9, 11)) {
            "unsupported HUD Lab gaode=$gaodeCode"
        }
        require(maneuverIconPng.isNotEmpty()) { "HUD Lab f8 PNG is empty" }
        return buildFrameWithRawF28(
            rawF28 = gaodeToF28(gaodeCode),
            distanceMeters = 100,
            road = "HUD LAB f8=0x${gaodeCode.toString(16)}",
            etaString = null,
            totalDistMeters = 0,
            speedLimit = 0,
            maneuverIconPng = maneuverIconPng,
            speedSignPng = null,
        )
    }

    /**
     * Exact bounded builder for the parked scenario matrix. [HudLabFrameSpec] exposes only fields
     * already present in the donor guidance frames; arbitrary protobuf fields cannot be injected.
     */
    fun buildHudLabScenarioFrame(
        spec: HudLabFrameSpec,
        maneuverIconPng: ByteArray?,
        speedSignPng: ByteArray?,
    ): ByteArray {
        require(spec.f28 == null || spec.f28 in setOf(1, 2, 3, 9)) {
            "unsupported HUD Lab f28=${spec.f28}"
        }
        require(spec.iconCode == null || spec.iconCode in setOf(0, 1, 2, 9, 11)) {
            "unsupported HUD Lab icon=${spec.iconCode}"
        }
        require((spec.iconCode != null) == (maneuverIconPng != null)) {
            "HUD Lab maneuver asset does not match frame spec"
        }
        require(!spec.includeSpeedSign || speedSignPng != null) {
            "HUD Lab speed-sign asset unavailable"
        }
        require(maneuverIconPng == null || maneuverIconPng.isNotEmpty()) {
            "HUD Lab f8 PNG is empty"
        }
        require(speedSignPng == null || speedSignPng.isNotEmpty()) {
            "HUD Lab f7 PNG is empty"
        }
        require(spec.distanceMeters >= 0 && spec.totalDistanceMeters >= 0) {
            "HUD Lab distances must be non-negative"
        }
        require(spec.speedLimit in 0..MAX_SPEED_LIMIT) {
            "HUD Lab speed limit out of range"
        }
        require(spec.road.length <= MAX_ROAD_CHARS) { "HUD Lab road text too long" }
        require(spec.etaString == null || spec.etaString.length <= MAX_ETA_CHARS) {
            "HUD Lab ETA text too long"
        }
        val payload = buildFrameWithRawF28(
            rawF28 = spec.f28,
            distanceMeters = spec.distanceMeters,
            road = spec.road,
            etaString = spec.etaString,
            totalDistMeters = spec.totalDistanceMeters,
            speedLimit = spec.speedLimit,
            maneuverIconPng = maneuverIconPng,
            speedSignPng = speedSignPng.takeIf { spec.includeSpeedSign },
        )
        require(payload.size <= MAX_PAYLOAD_BYTES) { "HUD Lab payload exceeds safe limit" }
        return payload
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
        // Do not manufacture donor metadata for an unknown Waze maneuver. The route card remains
        // visible and a later parsed A11Y/notification update adds both exact f8 and f28 values.
        rawF28?.let { writeVarintField(inner, 28, it.toLong()) }
        writeFixed64Field(inner, 33, progress(distanceMeters, totalDistMeters).toRawBits())
        return wrap(inner.toByteArray())
    }

    /** Bounded frame builder. The optional speed-sign PNG is dropped first. A corrupt/foreign
     * maneuver asset larger than Binder's safe payload is dropped only as a final fallback; donor
     * f28 metadata remains so the rest of the guidance frame can still be delivered. */
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

package com.bydmate.app.hud

import com.bydmate.app.navdata.NavManeuverCodes
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

    const val SEA_LION_F28_LEFT = 1
    const val SEA_LION_F28_RIGHT = 2
    const val SEA_LION_F28_SLIGHT_LEFT = 3
    const val SEA_LION_F28_SLIGHT_RIGHT = 5
    const val SEA_LION_F28_UTURN_LEFT = 7
    const val SEA_LION_F28_UTURN_RIGHT = 8
    const val SEA_LION_F28_STRAIGHT = 11
    const val SEA_LION_TURN_DISTANCE_METERS = 100

    /** GAODE maneuver -> donor f28 maneuver metadata. It is not the f8 PNG arrow itself. */
    fun gaodeToF28(gaode: Int): Int = when (gaode) {
        0 -> 0
        1, 3, 7 -> 3
        2, 4, 8 -> 2
        9, 10 -> 9
        else -> 1
    }

    /**
     * Sea Lion 07 (2025 CN) native-arrow mapping confirmed by the parked HUD Lab.
     *
     * The firmware enum is 1=left, 2=right, 3=slight left, 5=slight right, 7/8=U-turn left/right,
     * 11=straight, 99=no arrow (byd-hud `GMapsDirectManeuverMap.nativeFor`, field-tested on Sea
     * Lion 07). This car drew every one of them as expected in the parked HUD Lab (2026-07 and
     * 2026-10-03); 7, 8, 9 and 10 all draw a U-turn and no f28 value draws a roundabout. The real
     * Waze distance stays untouched so the firmware can apply its own near-turn threshold.
     * Uncalibrated maneuvers keep the route card but omit f28 rather than showing a false arrow.
     */
    fun seaLionF28ForGaode(gaode: Int): Int? = when (gaode) {
        NavManeuverCodes.GAODE_LEFT,
        NavManeuverCodes.GAODE_HARD_LEFT,
        -> SEA_LION_F28_LEFT

        NavManeuverCodes.GAODE_SLIGHT_LEFT -> SEA_LION_F28_SLIGHT_LEFT

        NavManeuverCodes.GAODE_RIGHT,
        NavManeuverCodes.GAODE_HARD_RIGHT,
        -> SEA_LION_F28_RIGHT

        NavManeuverCodes.GAODE_SLIGHT_RIGHT -> SEA_LION_F28_SLIGHT_RIGHT

        NavManeuverCodes.GAODE_UTURN -> SEA_LION_F28_UTURN_LEFT
        NavManeuverCodes.GAODE_UTURN_RIGHT -> SEA_LION_F28_UTURN_RIGHT
        NavManeuverCodes.GAODE_STRAIGHT -> SEA_LION_F28_STRAIGHT

        else -> null
    }

    /**
     * Beyond the 100 m approach every maneuver is "continue straight" on the glass. A known turn
     * was already sent as straight there; an unknown or glyph-less one (roundabout, arrival) now
     * matches it, so the arrow no longer disappears for a second after every passed turn.
     * Zero is the route hub's unknown/expired distance, never evidence of a nearby turn.
     */
    fun seaLionF28ForGuidance(gaode: Int, distanceMeters: Int): Int? {
        if (distanceMeters > SEA_LION_TURN_DISTANCE_METERS) return SEA_LION_F28_STRAIGHT
        val maneuver = seaLionF28ForGaode(gaode) ?: return null
        return if (distanceMeters >= 1) maneuver else SEA_LION_F28_STRAIGHT
    }

    /**
     * Production guidance frame for the confirmed Sea Lion 07 SOME/IP contract.
     *
     * The vehicle accepted the scalar route fields but rejected every tested f7/f8 PNG payload.
     * Send straight guidance outside the 100 m approach, keeping the actual Waze distance.
     * The firmware can still delay a turn at the boundary (parked 100 m tests stayed straight).
     * f10 road text is confirmed. f11 speed, f26 ETA and non-zero f33 progress are omitted because
     * the parked matrix did not prove that the Sea Lion firmware renders them.
     */
    fun buildSeaLionGuidanceFrame(
        maneuverGaode: Int,
        distanceMeters: Int,
        road: String,
    ): ByteArray {
        val safeRoad = road.take(MAX_ROAD_CHARS)
        val payload = buildFrameWithRawF28(
            rawF28 = seaLionF28ForGuidance(maneuverGaode, distanceMeters),
            distanceMeters = distanceMeters.coerceAtLeast(0),
            road = safeRoad,
            etaString = null,
            totalDistMeters = 0,
            speedLimit = 0,
            maneuverIconPng = null,
            speedSignPng = null,
        )
        check(payload.size <= MAX_PAYLOAD_BYTES) { "Sea Lion HUD payload exceeds safe limit" }
        return payload
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

    /** Schema fields a parked probe may add, with the only encoding each one accepts. */
    private val LAB_EXTRA_VARINT_FIELDS = setOf(3, 4, 5, 12, 13, 14, 15, 17, 18, 23)
    private val LAB_EXTRA_TEXT_FIELDS = setOf(24, 25, 27, 29)
    private val LAB_EXTRA_PACKED_FIELDS = setOf(7, 8)
    private const val LAB_EXTRA_MAX_VARINT = 100_000L
    private const val LAB_EXTRA_MAX_TEXT_CHARS = 120
    private const val LAB_EXTRA_MAX_PACKED_VALUES = 8

    /**
     * Exact bounded builder for the parked scenario matrix. [HudLabFrameSpec] exposes the donor
     * guidance fields plus a fixed list of this firmware's schema fields, each with bounded values
     * and its schema encoding; arbitrary protobuf fields cannot be injected.
     */
    fun buildHudLabScenarioFrame(
        spec: HudLabFrameSpec,
        maneuverIconPng: ByteArray?,
        speedSignPng: ByteArray?,
        f2Value: Int = 2,
    ): ByteArray {
        require(spec.effectiveRenderClass in setOf(1, 6)) {
            "unsupported HUD Lab f6=${spec.effectiveRenderClass}"
        }
        require(
            spec.f28 == null || spec.f28 in HudF28ExplorerCatalog.donorValues ||
                spec.f28 in HudLabScenarioCatalog.SEARCH_F28_VALUES,
        ) {
            "unsupported HUD Lab f28=${spec.f28}"
        }
        require(
            spec.iconCode == null || spec.iconCode in setOf(0, 1, 2, 9, 11) ||
                spec.iconCode == HudLabScenarioCatalog.SEARCH_ROUNDABOUT_ICON,
        ) {
            "unsupported HUD Lab icon=${spec.iconCode}"
        }
        require(f2Value in 0..255) { "HUD Lab f2 out of range" }
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
        requireLabExtras(spec)
        val payload = buildFrameWithRawF28(
            f2 = f2Value.toLong(),
            renderClass = spec.effectiveRenderClass,
            rawF28 = spec.f28,
            distanceMeters = spec.distanceMeters,
            road = spec.road,
            etaString = spec.etaString,
            totalDistMeters = spec.totalDistanceMeters,
            speedLimit = spec.speedLimit,
            maneuverIconPng = maneuverIconPng,
            speedSignPng = speedSignPng.takeIf { spec.includeSpeedSign },
            omitDistance = spec.omitDistance,
            extras = spec.extras,
        )
        require(payload.size <= MAX_PAYLOAD_BYTES) { "HUD Lab payload exceeds safe limit" }
        return payload
    }

    private fun requireLabExtras(spec: HudLabFrameSpec) {
        val fields = spec.extras.map(HudLabExtraField::field)
        require(fields.size == fields.toSet().size) { "HUD Lab extra field repeated" }
        spec.extras.forEach { extra ->
            when (extra) {
                is HudLabExtraField.Varint -> {
                    require(extra.field in LAB_EXTRA_VARINT_FIELDS) { "HUD Lab f${extra.field} is not a number field" }
                    require(extra.value in 0..LAB_EXTRA_MAX_VARINT) { "HUD Lab f${extra.field} out of range" }
                }
                is HudLabExtraField.Text -> {
                    require(extra.field in LAB_EXTRA_TEXT_FIELDS) { "HUD Lab f${extra.field} is not a text field" }
                    require(extra.value.length <= LAB_EXTRA_MAX_TEXT_CHARS) { "HUD Lab f${extra.field} text too long" }
                }
                is HudLabExtraField.Packed -> {
                    require(extra.field in LAB_EXTRA_PACKED_FIELDS) { "HUD Lab f${extra.field} is not a lane array" }
                    require(extra.values.size in 1..LAB_EXTRA_MAX_PACKED_VALUES) { "HUD Lab f${extra.field} lane count" }
                    require(extra.values.all { it in 0..255 }) { "HUD Lab f${extra.field} lane code out of range" }
                }
            }
        }
        require(spec.extras.none { it.field == 7 } || !spec.includeSpeedSign) { "HUD Lab f7 already holds a picture" }
        require(spec.extras.none { it.field == 8 } || spec.iconCode == null) { "HUD Lab f8 already holds a picture" }
    }

    private fun buildFrameWithRawF28(
        f2: Long = 2L,
        renderClass: Int? = null,
        rawF28: Int?,
        distanceMeters: Int,
        road: String,
        etaString: String?,
        totalDistMeters: Int,
        speedLimit: Int,
        maneuverIconPng: ByteArray?,
        speedSignPng: ByteArray?,
        omitDistance: Boolean = false,
        extras: List<HudLabExtraField> = emptyList(),
    ): ByteArray {
        val inner = ByteArrayOutputStream()
        // Lab-only schema fields go in field-number order between the donor fields.
        fun extrasIn(range: IntRange) = extras.filter { it.field in range }.sortedBy { it.field }
            .forEach { writeLabExtra(inner, it) }
        // f2 is the constant 2 in every reference guidance frame (donor stage 6,
        // 1779/1779 discope events); only the clear frame and lab framing probes count here.
        writeVarintField(inner, 2, f2)
        extrasIn(3..5)
        writeVarintField(
            inner,
            6,
            (renderClass ?: if (speedSignPng != null) 6 else 1).toLong(),
        )
        if (speedSignPng != null) writeBytesField(inner, 7, speedSignPng)
        extrasIn(7..7)
        if (maneuverIconPng != null) writeBytesField(inner, 8, maneuverIconPng)
        extrasIn(8..8)
        if (!omitDistance) writeVarintField(inner, 9, distanceMeters.toLong())
        if (road.isNotEmpty()) writeBytesField(inner, 10, road.toByteArray(Charsets.UTF_8))
        if (speedLimit > 0) writeVarintField(inner, 11, speedLimit.toLong())
        extrasIn(12..15)
        writeVarintField(inner, 16, 2L)
        extrasIn(17..25)
        if (etaString != null) writeBytesField(inner, 26, etaString.toByteArray(Charsets.UTF_8))
        extrasIn(27..27)
        // Do not manufacture donor metadata for an unknown Waze maneuver. The route card remains
        // visible and a later parsed A11Y/notification update adds both exact f8 and f28 values.
        rawF28?.let { writeVarintField(inner, 28, it.toLong()) }
        extrasIn(29..32)
        writeFixed64Field(inner, 33, progress(distanceMeters, totalDistMeters).toRawBits())
        return wrap(inner.toByteArray())
    }

    private fun writeLabExtra(out: ByteArrayOutputStream, extra: HudLabExtraField) {
        when (extra) {
            is HudLabExtraField.Varint -> writeVarintField(out, extra.field, extra.value)
            is HudLabExtraField.Text ->
                writeBytesField(out, extra.field, extra.value.toByteArray(Charsets.UTF_8))
            is HudLabExtraField.Packed -> {
                val packed = ByteArrayOutputStream()
                extra.values.forEach { writeVarint(packed, it.toLong()) }
                writeBytesField(out, extra.field, packed.toByteArray())
            }
        }
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

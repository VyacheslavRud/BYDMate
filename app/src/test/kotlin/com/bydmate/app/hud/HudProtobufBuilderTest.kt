package com.bydmate.app.hud

import com.bydmate.app.navdata.NavManeuverCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HudProtobufBuilderTest {

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    /** Minimal protobuf wire reader for assertions (varint/length-delimited/fixed64). */
    private class ProtoReader(private val bytes: ByteArray) {
        var pos = 0
        val fields = mutableMapOf<Int, MutableList<Any>>()

        fun parse(): Map<Int, List<Any>> {
            while (pos < bytes.size) {
                val tag = readVarint().toInt()
                val fieldNo = tag ushr 3
                when (tag and 7) {
                    0 -> fields.getOrPut(fieldNo) { mutableListOf() }.add(readVarint())
                    1 -> fields.getOrPut(fieldNo) { mutableListOf() }.add(readFixed64())
                    2 -> {
                        val len = readVarint().toInt()
                        fields.getOrPut(fieldNo) { mutableListOf() }
                            .add(bytes.copyOfRange(pos, pos + len))
                        pos += len
                    }
                    else -> error("unsupported wire type")
                }
            }
            return fields
        }

        private fun readVarint(): Long {
            var result = 0L; var shift = 0
            while (true) {
                val b = bytes[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b < 0x80) return result
                shift += 7
            }
        }

        private fun readFixed64(): Long {
            var v = 0L
            repeat(8) { i -> v = v or ((bytes[pos + i].toLong() and 0xFF) shl (8 * i)) }
            pos += 8
            return v
        }
    }

    private fun unwrap(payload: ByteArray): Map<Int, List<Any>> {
        assertEquals(0x0A, payload[0].toInt())
        var pos = 1; var len = 0L; var shift = 0
        while (true) {
            val b = payload[pos++].toInt() and 0xFF
            len = len or ((b and 0x7F).toLong() shl shift)
            if (b < 0x80) break
            shift += 7
        }
        assertEquals(len.toInt(), payload.size - pos)
        return ProtoReader(payload.copyOfRange(pos, payload.size)).parse()
    }

    @Test fun `golden frame matches reference byte layout`() {
        val payload = HudProtobufBuilder.buildFrameSafe(
            maneuverGaode = 2, distanceMeters = 250, road = "A",
            etaString = "10:10", totalDistMeters = 1000, speedLimit = 60,
            maneuverIconPng = byteArrayOf(0x01), speedSignPng = null,
        )
        assertEquals(
            "0a271002300142010148fa01520141583c800102d2010531303a3130e001028902000000000000e83f",
            payload.hex(),
        )
    }

    @Test fun `golden clear frame bytes`() {
        assertEquals("0a08102a30ff01800101", HudProtobufBuilder.buildClearFrame(42).hex())
    }

    @Test fun `frame fields and order`() {
        val icon = byteArrayOf(1, 2, 3)
        val sign = byteArrayOf(9, 8)
        val payload = HudProtobufBuilder.buildFrame(
            maneuverGaode = 1, distanceMeters = 250, road = "ул. Ленина",
            etaString = "18:40", totalDistMeters = 5000, speedLimit = 60,
            maneuverIconPng = icon, speedSignPng = sign,
        )
        val f = unwrap(payload)
        assertEquals(2L, (f[2]!![0] as Long))
        assertEquals(6L, (f[6]!![0] as Long))           // with speed sign -> render class 6
        assertTrue((f[7]!![0] as ByteArray).contentEquals(sign))
        assertTrue((f[8]!![0] as ByteArray).contentEquals(icon))
        assertEquals(250L, (f[9]!![0] as Long))
        assertEquals("ул. Ленина", String(f[10]!![0] as ByteArray, Charsets.UTF_8))
        assertEquals(60L, (f[11]!![0] as Long))
        assertEquals(2L, (f[16]!![0] as Long))
        assertEquals("18:40", String(f[26]!![0] as ByteArray, Charsets.UTF_8))
        assertEquals(3L, (f[28]!![0] as Long))          // left -> 3
        val progressBits = f[33]!![0] as Long
        val progress = Double.fromBits(progressBits)
        assertEquals(1.0 - 250.0 / 5000.0, progress, 1e-9)
        assertNull(f[3]); assertNull(f[4]); assertNull(f[12]); assertNull(f[17])
    }

    @Test fun `no speed sign means render class 1 and no f7`() {
        val f = unwrap(HudProtobufBuilder.buildFrame(
            maneuverGaode = 2, distanceMeters = 100, road = "",
            etaString = null, totalDistMeters = 0, speedLimit = 0,
            maneuverIconPng = byteArrayOf(1), speedSignPng = null,
        ))
        assertEquals(1L, (f[6]!![0] as Long))
        assertNull(f[7])
        assertNull(f[10])   // empty road omitted
        assertNull(f[11])   // zero speed limit omitted
        assertNull(f[26])   // null eta omitted
    }

    @Test fun `clear frame has render class 255 and f16=1`() {
        val f = unwrap(HudProtobufBuilder.buildClearFrame(3))
        assertEquals(3L, (f[2]!![0] as Long))
        assertEquals(255L, (f[6]!![0] as Long))
        assertEquals(1L, (f[16]!![0] as Long))
        assertNull(f[8]); assertNull(f[9])
    }

    @Test fun `gaode to f28 reference maneuver`() {
        assertEquals(3, HudProtobufBuilder.gaodeToF28(1))    // left
        assertEquals(3, HudProtobufBuilder.gaodeToF28(3))
        assertEquals(3, HudProtobufBuilder.gaodeToF28(7))
        assertEquals(2, HudProtobufBuilder.gaodeToF28(2))    // right
        assertEquals(2, HudProtobufBuilder.gaodeToF28(4))
        assertEquals(2, HudProtobufBuilder.gaodeToF28(8))
        assertEquals(9, HudProtobufBuilder.gaodeToF28(9))    // uturn
        assertEquals(9, HudProtobufBuilder.gaodeToF28(10))
        assertEquals(1, HudProtobufBuilder.gaodeToF28(11))   // straight & known fallbacks
        assertEquals(1, HudProtobufBuilder.gaodeToF28(13))
        assertEquals(0, HudProtobufBuilder.gaodeToF28(0))    // unknown; field is omitted
    }

    @Test fun `unknown maneuver omits misleading fallback direction field`() {
        val frame = HudProtobufBuilder.buildFrame(
            maneuverGaode = 0,
            distanceMeters = 500,
            road = "A",
            etaString = null,
            totalDistMeters = 0,
            speedLimit = 0,
            maneuverIconPng = null,
            speedSignPng = null,
        )

        val fields = unwrap(frame)
        assertNull(fields[28])
        assertNull(fields[8])
        assertEquals(500L, fields[9]!![0] as Long)
    }

    @Test fun `Sea Lion production frame contains only confirmed scalar fields`() {
        val fields = unwrap(
            HudProtobufBuilder.buildSeaLionGuidanceFrame(
                maneuverGaode = NavManeuverCodes.GAODE_RIGHT,
                distanceMeters = 50,
                road = "Road",
                etaString = " 12:34 ",
            ),
        )

        assertEquals(setOf(2, 6, 9, 10, 16, 26, 28, 33), fields.keys)
        assertEquals(2L, fields[2]!!.single() as Long)
        assertEquals(1L, fields[6]!!.single() as Long)
        assertEquals(50L, fields[9]!!.single() as Long)
        assertEquals("Road", String(fields[10]!!.single() as ByteArray, Charsets.UTF_8))
        assertEquals("12:34", String(fields[26]!!.single() as ByteArray, Charsets.UTF_8))
        assertEquals(2L, fields[28]!!.single() as Long)
        assertEquals(0.0, Double.fromBits(fields[33]!!.single() as Long), 0.0)
        assertNull(fields[7])
        assertNull(fields[8])
        assertNull(fields[11])
    }

    @Test fun `Sea Lion production mapping emits only confirmed left and right values`() {
        listOf(
            NavManeuverCodes.GAODE_LEFT,
            NavManeuverCodes.GAODE_SLIGHT_LEFT,
            NavManeuverCodes.GAODE_HARD_LEFT,
        ).forEach { gaode -> assertEquals(3, HudProtobufBuilder.seaLionF28ForGaode(gaode)) }
        listOf(
            NavManeuverCodes.GAODE_RIGHT,
            NavManeuverCodes.GAODE_SLIGHT_RIGHT,
            NavManeuverCodes.GAODE_HARD_RIGHT,
        ).forEach { gaode -> assertEquals(2, HudProtobufBuilder.seaLionF28ForGaode(gaode)) }
        listOf(
            0,
            NavManeuverCodes.GAODE_STRAIGHT,
            NavManeuverCodes.GAODE_UTURN,
            NavManeuverCodes.GAODE_UTURN_RIGHT,
            NavManeuverCodes.GAODE_ROUNDABOUT_ENTER,
            NavManeuverCodes.GAODE_ROUNDABOUT_EXIT,
            NavManeuverCodes.GAODE_WAYPOINT,
            NavManeuverCodes.GAODE_FERRY,
            NavManeuverCodes.GAODE_ARRIVE,
            NavManeuverCodes.GAODE_TUNNEL,
        ).forEach { gaode -> assertNull(HudProtobufBuilder.seaLionF28ForGaode(gaode)) }
    }

    @Test fun `Sea Lion production frame preserves real distance for firmware threshold`() {
        listOf(20, 50, 100, 500).forEach { distance ->
            val fields = unwrap(
                HudProtobufBuilder.buildSeaLionGuidanceFrame(
                    maneuverGaode = NavManeuverCodes.GAODE_RIGHT,
                    distanceMeters = distance,
                    road = "",
                    etaString = null,
                ),
            )
            assertEquals(distance.toLong(), fields[9]!!.single() as Long)
            assertEquals(2L, fields[28]!!.single() as Long)
        }
    }

    @Test fun `Sea Lion uncalibrated maneuver keeps information without a false arrow`() {
        val fields = unwrap(
            HudProtobufBuilder.buildSeaLionGuidanceFrame(
                maneuverGaode = NavManeuverCodes.GAODE_STRAIGHT,
                distanceMeters = 500,
                road = "A",
                etaString = null,
            ),
        )

        assertEquals(500L, fields[9]!!.single() as Long)
        assertEquals("A", String(fields[10]!!.single() as ByteArray, Charsets.UTF_8))
        assertNull(fields[28])
    }

    @Test fun `Sea Lion production frame bounds external Waze values`() {
        val fields = unwrap(
            HudProtobufBuilder.buildSeaLionGuidanceFrame(
                maneuverGaode = NavManeuverCodes.GAODE_LEFT,
                distanceMeters = -1,
                road = "x".repeat(HudProtobufBuilder.MAX_ROAD_CHARS + 50),
                etaString = " 12345678901234567890 ",
            ),
        )

        assertEquals(0L, fields[9]!!.single() as Long)
        assertEquals(
            HudProtobufBuilder.MAX_ROAD_CHARS,
            String(fields[10]!!.single() as ByteArray, Charsets.UTF_8).length,
        )
        assertEquals(
            "1234567890123456",
            String(fields[26]!!.single() as ByteArray, Charsets.UTF_8),
        )
    }

    @Test fun `HUD Lab frame sends exact raw f28 without PNG`() {
        listOf(1, 2, 3, 9).forEach { rawF28 ->
            val fields = unwrap(HudProtobufBuilder.buildHudLabFrame(rawF28))
            assertEquals(rawF28.toLong(), fields[28]!![0] as Long)
            assertNull(fields[8])
            assertEquals(100L, fields[9]!![0] as Long)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `HUD Lab rejects uncalibrated raw f28`() {
        HudProtobufBuilder.buildHudLabFrame(255)
    }

    @Test fun `HUD Lab live frames include exact f8 icons and matching donor metadata`() {
        mapOf(1 to 3L, 2 to 2L, 9 to 9L, 11 to 1L).forEach { (gaode, rawF28) ->
            val icon = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, gaode.toByte())

            val fields = unwrap(HudProtobufBuilder.buildHudLabLiveFrame(gaode, icon))

            assertTrue((fields[8]!![0] as ByteArray).contentEquals(icon))
            assertEquals(rawF28, fields[28]!![0] as Long)
            assertEquals(100L, fields[9]!![0] as Long)
            assertEquals(1L, fields[6]!![0] as Long)
            assertNull(fields[7])
        }
    }

    @Test fun `every HUD Lab matrix frame encodes only bounded donor fields`() {
        val allowedFields = setOf(2, 6, 7, 8, 9, 10, 11, 16, 26, 28, 33)
        HudLabScenarioCatalog.all
            .flatMap { it.steps }
            .filterIsInstance<HudLabScenarioStep.Send>()
            .forEach { step ->
                val icon = step.frame.iconCode?.let { byteArrayOf(0x01, it.toByte()) }
                val sign = if (step.frame.includeSpeedSign) byteArrayOf(0x02, 0x3c) else null

                val fields = unwrap(
                    HudProtobufBuilder.buildHudLabScenarioFrame(step.frame, icon, sign),
                )

                assertTrue("${step.label}: ${fields.keys}", fields.keys.all { it in allowedFields })
                assertEquals(2L, fields[2]!!.single() as Long)
                assertEquals(2L, fields[16]!!.single() as Long)
                assertEquals(step.frame.iconCode != null, fields[8] != null)
                assertEquals(step.frame.includeSpeedSign, fields[7] != null)
                assertEquals(step.frame.f28?.toLong(), fields[28]?.singleOrNull() as Long?)
            }
    }

    @Test fun `HUD Lab can isolate donor render class six without PNG`() {
        val spec = HudLabFrameSpec(
            renderClass = 6,
            distanceMeters = 50,
            road = "",
            speedLimit = 50,
        )

        val fields = unwrap(
            HudProtobufBuilder.buildHudLabScenarioFrame(spec, null, null),
        )

        assertEquals(6L, fields[6]!!.single() as Long)
        assertEquals(50L, fields[11]!!.single() as Long)
        assertNull(fields[7])
        assertNull(fields[8])
    }

    @Test fun `HUD Lab can encode bounded arrow-free f28 zero candidate`() {
        val spec = HudLabFrameSpec(
            renderClass = 6,
            f28 = 0,
            distanceMeters = 50,
            road = "",
            speedLimit = 50,
        )

        val fields = unwrap(HudProtobufBuilder.buildHudLabScenarioFrame(spec, null, null))

        assertEquals(0L, fields[28]!!.single() as Long)
        assertNull(fields[7])
        assertNull(fields[8])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `HUD Lab live frame rejects maneuver outside calibration set`() {
        HudProtobufBuilder.buildHudLabLiveFrame(49, byteArrayOf(1))
    }

    @Test fun `oversize payload drops speed sign but never maneuver icon`() {
        val bigIcon = ByteArray(40_000) { 1 }
        val bigSign = ByteArray(40_000) { 2 }
        val payload = HudProtobufBuilder.buildFrameSafe(
            maneuverGaode = 2, distanceMeters = 100, road = "x",
            etaString = null, totalDistMeters = 0, speedLimit = 60,
            maneuverIconPng = bigIcon, speedSignPng = bigSign,
        )
        assertTrue(payload.size <= HudProtobufBuilder.MAX_PAYLOAD_BYTES)
        val f = unwrap(payload)
        assertNull(f[7])
        assertTrue((f[8]!![0] as ByteArray).contentEquals(bigIcon))
        assertEquals(1L, (f[6]!![0] as Long))   // sign dropped -> render class back to 1
    }

    @Test fun `oversized road text cannot overflow the payload limit`() {
        val frame = HudProtobufBuilder.buildFrameSafe(
            maneuverGaode = 2, distanceMeters = 500, road = "х".repeat(100_000),
            etaString = "12:34", totalDistMeters = 10_000, speedLimit = 60,
            maneuverIconPng = null, speedSignPng = null)
        assertTrue(frame.size <= HudProtobufBuilder.MAX_PAYLOAD_BYTES)
    }

    @Test fun `corrupt oversized maneuver icon is dropped as final payload fallback`() {
        val frame = HudProtobufBuilder.buildFrameSafe(
            maneuverGaode = 2, distanceMeters = 500, road = "A",
            etaString = "12:34", totalDistMeters = 10_000, speedLimit = 60,
            maneuverIconPng = ByteArray(HudProtobufBuilder.MAX_PAYLOAD_BYTES + 1),
            speedSignPng = null,
        )
        assertTrue(frame.size <= HudProtobufBuilder.MAX_PAYLOAD_BYTES)
        val f = unwrap(frame)
        assertNull(f[8])
        assertEquals(2L, f[28]!![0] as Long)
    }

    @Test fun `safe frame clamps negative numeric values and bounds eta`() {
        val frame = HudProtobufBuilder.buildFrameSafe(
            maneuverGaode = 2, distanceMeters = -1, road = "A",
            etaString = " 12345678901234567890 ", totalDistMeters = -2, speedLimit = -3,
            maneuverIconPng = null, speedSignPng = null,
        )
        val f = unwrap(frame)
        assertEquals(0L, f[9]!![0] as Long)
        assertNull(f[11])
        assertEquals(
            "1234567890123456",
            String(f[26]!![0] as ByteArray, Charsets.UTF_8),
        )
    }

    @Test fun `safe frame caps implausible speed limit`() {
        val frame = HudProtobufBuilder.buildFrameSafe(
            maneuverGaode = 2, distanceMeters = 100, road = "A",
            etaString = null, totalDistMeters = 1_000, speedLimit = Int.MAX_VALUE,
            maneuverIconPng = null, speedSignPng = null,
        )

        assertEquals(
            HudProtobufBuilder.MAX_SPEED_LIMIT.toLong(),
            unwrap(frame)[11]!![0] as Long,
        )
    }

    @Test fun `progress clamped to 0-1`() {
        val f = unwrap(HudProtobufBuilder.buildFrame(
            maneuverGaode = 2, distanceMeters = 9000, road = "",
            etaString = null, totalDistMeters = 5000, speedLimit = 0,
            maneuverIconPng = null, speedSignPng = null,
        ))
        val progress = Double.fromBits(f[33]!![0] as Long)
        assertEquals(0.0, progress, 1e-9)
    }
}

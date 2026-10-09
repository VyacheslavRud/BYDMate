package com.bydmate.app.hud

/** Stable groups shared by the Sea Lion smoke tests and compatibility calibration catalog. */
enum class HudLabScenarioGroup {
    SEA_LION_CONFIRMED,
    UTURN,
    ROUNDABOUT,
    SPEED_LIMIT,
    CONTROL,
    /** No-CLEAR arrow changes: does the firmware redraw a changed f28 by itself? */
    TRANSITION,
    /** Selector and picture probes looking for a roundabout symbol on this firmware. */
    ROUNDABOUT_SEARCH,
    /** Fields of this firmware's HudRoadInfoNotifyStruct that production has never sent. */
    FIELD_PROBE,
}

/**
 * One extra field of the firmware's `someip.hud.navi.info.service` schema, extracted from
 * `libsomeipimpl_proto.so` of this car. Only the parked lab sends them, in field-number order.
 */
sealed interface HudLabExtraField {
    val field: Int
    val manifestToken: String

    data class Varint(override val field: Int, val value: Long) : HudLabExtraField {
        override val manifestToken: String
            get() = "f${this.field}=$value"
    }

    data class Text(override val field: Int, val value: String) : HudLabExtraField {
        override val manifestToken: String
            get() = "f${this.field}=text"
    }

    /** Proto3 packed `repeated uint32`, the lane arrays f7/f8 on this firmware. */
    data class Packed(override val field: Int, val values: List<Int>) : HudLabExtraField {
        override val manifestToken: String
            get() = "f${this.field}=${values.joinToString("/", "[", "]")}"
    }
}

/** Bounded donor fields allowed in parked synthetic frames. */
data class HudLabFrameSpec(
    val renderClass: Int? = null,
    val f28: Int? = null,
    val iconCode: Int? = null,
    val distanceMeters: Int = 100,
    val road: String = "HUD LAB",
    val etaString: String? = null,
    val totalDistanceMeters: Int = 0,
    val speedLimit: Int = 0,
    val includeSpeedSign: Boolean = false,
    /**
     * byd-hud framing, field-tested on Sea Lion 07: f2 counts frames 0..255 instead of the
     * constant 2, and the Binder call carries the wall-clock time instead of 0. Lab-only probes
     * for why this firmware refuses picture frames; production keeps the confirmed framing.
     */
    val f2Counter: Boolean = false,
    val wallClockTimestamp: Boolean = false,
    /** Leaves f9 out: some BYD HUDs then show their own "now" label (byd-hud). */
    val omitDistance: Boolean = false,
    val extras: List<HudLabExtraField> = emptyList(),
) {
    val effectiveRenderClass: Int
        get() = renderClass ?: if (includeSpeedSign) 6 else 1

    val fieldManifest: String
        get() = buildList {
            add(2 to if (f2Counter) "f2=counter" else "f2=2")
            add(6 to "f6=$effectiveRenderClass")
            if (includeSpeedSign) add(7 to "f7=speed_png")
            iconCode?.let { add(8 to "f8=0x${it.toString(16)}.png") }
            if (!omitDistance) add(9 to "f9=$distanceMeters")
            if (road.isNotEmpty()) add(10 to "f10=text")
            if (speedLimit > 0) add(11 to "f11=$speedLimit")
            add(16 to "f16=2")
            etaString?.let { add(26 to "f26=eta") }
            f28?.let { add(28 to "f28=$it") }
            val progress = if (totalDistanceMeters > 0) {
                (1.0 - distanceMeters.toDouble() / totalDistanceMeters).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            add(33 to "f33=$progress")
            extras.forEach { add(it.field to it.manifestToken) }
        }.sortedBy { it.first }.joinToString(",") { it.second }
}

sealed interface HudLabScenarioStep {
    val gapBeforeMs: Long

    data class Send(
        val label: String,
        val frame: HudLabFrameSpec,
        val repeatCount: Int,
        val cadenceMs: Long,
        override val gapBeforeMs: Long = 0L,
    ) : HudLabScenarioStep

    data class Clear(
        val attempts: Int = 1,
        override val gapBeforeMs: Long = 0L,
    ) : HudLabScenarioStep
}

data class HudLabScenario(
    val id: String,
    val group: HudLabScenarioGroup,
    val title: String,
    val command: HudLabCommand?,
    val expected: HudLabObserved,
    val steps: List<HudLabScenarioStep>,
    /** Answer buttons for this probe; empty keeps the screen's id-prefix defaults. */
    val observations: List<HudLabObserved> = emptyList(),
) {
    val summary: String = steps.joinToString(" -> ") { step ->
        when (step) {
            is HudLabScenarioStep.Clear -> "CLEAR×${step.attempts}"
            is HudLabScenarioStep.Send ->
                "${step.label}×${step.repeatCount}@${step.cadenceMs}ms[${step.frame.fieldManifest}]"
        }
    }
}

/** Confirmed Sea Lion smoke tests plus older compatibility probes kept for other vehicles. */
object HudLabScenarioCatalog {
    private const val BURST_COUNT = 10
    private const val BURST_CADENCE_MS = 300L
    private const val CLEAR_GAP_MS = 350L

    private fun send(
        label: String,
        frame: HudLabFrameSpec,
        repeat: Int = BURST_COUNT,
        cadenceMs: Long = BURST_CADENCE_MS,
        gapBeforeMs: Long = 0L,
    ) = HudLabScenarioStep.Send(label, frame, repeat, cadenceMs, gapBeforeMs)

    private fun burst(label: String, frame: HudLabFrameSpec): List<HudLabScenarioStep> = listOf(
        HudLabScenarioStep.Clear(attempts = 3),
        send(label, frame, gapBeforeMs = CLEAR_GAP_MS),
    )

    private fun confirmedSeaLion(
        id: String,
        title: String,
        frame: HudLabFrameSpec,
        expected: HudLabObserved,
    ) = HudLabScenario(
        id = id,
        group = HudLabScenarioGroup.SEA_LION_CONFIRMED,
        title = title,
        command = null,
        expected = expected,
        steps = burst(id.lowercase(), frame),
    )

    /**
     * Minimal production contract confirmed on the 2025 Chinese-market Sea Lion 07.
     *
     * f9 is the real maneuver distance, f10 is optional road text and f28 is emitted only for the
     * two calibrated directions. The firmware itself keeps the card straight at 100 m and switches
     * to the native turn arrow at 20/50 m. No f7/f8 PNG, f11 speed, f26 ETA or non-zero progress is
     * included in these repeatable smoke tests.
     */
    val confirmed: List<HudLabScenario> = listOf(
        confirmedSeaLion(
            "SL01", "CONFIRMED · RIGHT at 50 m",
            HudLabFrameSpec(f28 = 2, distanceMeters = 50, road = ""),
            HudLabObserved.RIGHT,
        ),
        confirmedSeaLion(
            "SL02", "CONFIRMED · LEFT at 50 m",
            HudLabFrameSpec(f28 = 3, distanceMeters = 50, road = ""),
            HudLabObserved.LEFT,
        ),
        confirmedSeaLion(
            "SL03", "FIRMWARE THRESHOLD · RIGHT at 100 m",
            HudLabFrameSpec(f28 = 2, distanceMeters = 100, road = ""),
            HudLabObserved.STRAIGHT,
        ),
        confirmedSeaLion(
            "SL04", "FIRMWARE THRESHOLD · LEFT at 100 m",
            HudLabFrameSpec(f28 = 3, distanceMeters = 100, road = ""),
            HudLabObserved.STRAIGHT,
        ),
        confirmedSeaLion(
            "SL05", "CONFIRMED · RIGHT + road text",
            HudLabFrameSpec(f28 = 2, distanceMeters = 50, road = "HUD LAB ROAD"),
            HudLabObserved.ROAD_VISIBLE,
        ),
    )

    private fun directionCandidate(
        id: String,
        group: HudLabScenarioGroup,
        command: HudLabCommand,
        distanceMeters: Int,
    ) = HudLabScenario(
        id = id,
        group = group,
        title = "${command.name} · ${distanceMeters} m",
        command = command,
        expected = command.expected,
        steps = burst(
            label = "${command.name.lowercase()}_${distanceMeters}m",
            frame = HudLabFrameSpec(
                f28 = command.rawF28,
                distanceMeters = distanceMeters,
                road = "HUD LAB ${command.name}",
            ),
        ),
    )

    private fun speedCandidate(id: String, speedLimit: Int) = HudLabScenario(
        id = id,
        group = HudLabScenarioGroup.SPEED_LIMIT,
        title = "SPEED_LIMIT f11=$speedLimit",
        command = null,
        expected = HudLabObserved.SPEED_NUMBER_VISIBLE,
        steps = burst(
            label = "speed_limit_$speedLimit",
            frame = HudLabFrameSpec(
                distanceMeters = 50,
                road = "",
                speedLimit = speedLimit,
            ),
        ),
    )

    private fun legacyCoexistence(
        id: String,
        title: String,
        rawF28: Int,
    ) = HudLabScenario(
        id = id,
        group = HudLabScenarioGroup.SPEED_LIMIT,
        title = title,
        command = null,
        expected = HudLabObserved.SPEED_NUMBER_WITH_MANEUVER_VISIBLE,
        steps = burst(
            id.lowercase(),
            HudLabFrameSpec(
                renderClass = 6,
                f28 = rawF28,
                distanceMeters = 50,
                road = "",
                speedLimit = 50,
            ),
        ),
    )

    /** Older working probes retained as a separate compatibility set for another BYD firmware. */
    val compatibility: List<HudLabScenario> = listOf(
        directionCandidate("U01", HudLabScenarioGroup.UTURN, HudLabCommand.UTURN, 20),
        directionCandidate("U02", HudLabScenarioGroup.UTURN, HudLabCommand.UTURN, 50),
        directionCandidate(
            "R01",
            HudLabScenarioGroup.ROUNDABOUT,
            HudLabCommand.ROUNDABOUT_ENTER,
            20,
        ),
        directionCandidate(
            "R02",
            HudLabScenarioGroup.ROUNDABOUT,
            HudLabCommand.ROUNDABOUT_EXIT,
            20,
        ),
        speedCandidate("S01", 50),
        speedCandidate("S02", 80),
        legacyCoexistence("N17", "LEGACY · RIGHT + f6=6 + f11=50", 2),
        legacyCoexistence("N18", "LEGACY · LEFT + f6=6 + f11=50", 3),
    )

    // HX01-HX05 were retired after the 2026-07-22 Sea Lion run: f11, f26, f33 and the f6=6
    // variant produced no visual output beyond the already-confirmed f28 arrow. Keeping them out
    // of `all` prevents accidental reruns while historical exported journals remain readable.
    val all: List<HudLabScenario> = confirmed + compatibility

    /**
     * f28 values worth one parked look for a roundabout glyph, most likely first: 7 and 10 were
     * seen as circular arrows in July, 8 is byd-hud's right U-turn, 9 the confirmed U-turn as a
     * reference, 4/5/6/12 sit between known codes, 99 is byd-hud's blank. Codes 13/24/45/46/48/49
     * already drew straight on this firmware and are not repeated.
     */
    val SEARCH_F28_VALUES: List<Int> = listOf(7, 10, 8, 9, 5, 4, 6, 12, 99)

    /** Donor roundabout picture with exit number 2 (assets/navi/0x1a.png). */
    const val SEARCH_ROUNDABOUT_ICON = 26

    private fun transition(
        id: String,
        title: String,
        first: HudLabFrameSpec,
        second: HudLabFrameSpec,
    ) = HudLabScenario(
        id = id,
        group = HudLabScenarioGroup.TRANSITION,
        title = title,
        command = null,
        expected = HudLabObserved.ARROW_CHANGED,
        steps = listOf(
            HudLabScenarioStep.Clear(attempts = 3),
            send("${id.lowercase()}_first", first, gapBeforeMs = CLEAR_GAP_MS),
            send("${id.lowercase()}_second", second, gapBeforeMs = BURST_CADENCE_MS),
        ),
    )

    private fun selectorProbe(rawF28: Int): HudLabScenario {
        val id = "K" + rawF28.toString(16).uppercase().padStart(2, '0')
        return HudLabScenario(
            id = id,
            group = HudLabScenarioGroup.ROUNDABOUT_SEARCH,
            title = "SEARCH · f28=$rawF28 at 50 m",
            command = null,
            expected = HudLabObserved.ROUNDABOUT,
            steps = burst(
                id.lowercase(),
                HudLabFrameSpec(f28 = rawF28, distanceMeters = 50, road = "HUD LAB $id"),
            ),
        )
    }

    private fun pictureProbe(
        id: String,
        title: String,
        f2Counter: Boolean,
        wallClockTimestamp: Boolean,
    ) = HudLabScenario(
        id = id,
        group = HudLabScenarioGroup.ROUNDABOUT_SEARCH,
        title = title,
        command = null,
        expected = HudLabObserved.ROUNDABOUT,
        steps = burst(
            id.lowercase(),
            HudLabFrameSpec(
                f28 = 99,
                iconCode = SEARCH_ROUNDABOUT_ICON,
                distanceMeters = 50,
                road = "HUD LAB $id",
                f2Counter = f2Counter,
                wallClockTimestamp = wallClockTimestamp,
            ),
        ),
    )

    /**
     * T01-T03 (answered 2026-10-03: the glass redraws a changed arrow without a CLEAR) and the
     * K/P roundabout probes (no roundabout selector; this firmware's f7/f8 are lane arrays, so
     * pictures are refused) stay runnable for comparison.
     */
    val search: List<HudLabScenario> = listOf(
        transition(
            "T01", "NO CLEAR · LEFT then RIGHT at 50 m",
            HudLabFrameSpec(f28 = 1, distanceMeters = 50, road = "HUD LAB T01"),
            HudLabFrameSpec(f28 = 2, distanceMeters = 50, road = "HUD LAB T01"),
        ),
        transition(
            "T02", "NO CLEAR · STRAIGHT at 150 m then RIGHT at 50 m",
            HudLabFrameSpec(f28 = 11, distanceMeters = 150, road = "HUD LAB T02"),
            HudLabFrameSpec(f28 = 2, distanceMeters = 50, road = "HUD LAB T02"),
        ),
        transition(
            "T03", "NO CLEAR · RIGHT at 50 m then STRAIGHT at 300 m",
            HudLabFrameSpec(f28 = 2, distanceMeters = 50, road = "HUD LAB T03"),
            HudLabFrameSpec(f28 = 11, distanceMeters = 300, road = "HUD LAB T03"),
        ),
    ) + SEARCH_F28_VALUES.map(::selectorProbe) + listOf(
        pictureProbe("P01", "PICTURE · roundabout, f2 counter + wall clock", true, true),
        pictureProbe("P02", "PICTURE · roundabout, wall clock only", false, true),
        pictureProbe("P03", "PICTURE · roundabout, f2 counter only", true, false),
    )

    /** The card every field probe is drawn on: the confirmed RIGHT at 50 m. */
    private val probeCard = HudLabFrameSpec(f28 = 2, distanceMeters = 50, road = "HUD LAB")

    private val textAnswers = listOf(
        HudLabObserved.TEXT_FULL,
        HudLabObserved.TEXT_CUT,
        HudLabObserved.TEXT_SCROLLS,
        HudLabObserved.TEXT_BROKEN_CHARS,
        HudLabObserved.NOTHING,
        HudLabObserved.OTHER,
        HudLabObserved.NOT_REPORTED,
    )
    private val distanceAnswers = listOf(
        HudLabObserved.DISTANCE_VISIBLE,
        HudLabObserved.DISTANCE_GLITCH,
        HudLabObserved.NOW_LABEL,
        HudLabObserved.NOTHING_NEW,
        HudLabObserved.NOTHING,
        HudLabObserved.OTHER,
        HudLabObserved.NOT_REPORTED,
    )

    private fun newElementAnswers(vararg found: HudLabObserved) = found.toList() + listOf(
        HudLabObserved.NOTHING_NEW,
        HudLabObserved.FLASHED,
        HudLabObserved.NOTHING,
        HudLabObserved.OTHER,
        HudLabObserved.NOT_REPORTED,
    )

    private fun probe(
        id: String,
        title: String,
        frame: HudLabFrameSpec,
        expected: HudLabObserved,
        observations: List<HudLabObserved>,
    ) = HudLabScenario(
        id = id,
        group = HudLabScenarioGroup.FIELD_PROBE,
        title = title,
        command = null,
        expected = expected,
        steps = burst(id.lowercase(), frame),
        observations = observations,
    )

    private fun extraProbe(
        id: String,
        title: String,
        expected: HudLabObserved,
        observations: List<HudLabObserved>,
        vararg extras: HudLabExtraField,
    ) = probe(
        id,
        title,
        probeCard.copy(road = "HUD LAB $id", extras = extras.toList()),
        expected,
        observations,
    )

    /**
     * One parked session over every field of this firmware's `HudRoadInfoNotifyStruct` that the
     * production frame has never sent, plus the text and distance behaviour new features rely on.
     * Each probe draws the confirmed RIGHT-at-50-m card and adds one thing, so "nothing new" is
     * a clear answer. Lane codes follow byd-hud's instrument vocabulary (0 straight, 1 left,
     * 3 right, 4 straight+right; 255 = lane not recommended). C01 checks a counting f2.
     */
    val fields: List<HudLabScenario> = listOf(
        probe(
            "C01", "FRAMING · RIGHT at 50 m, counting f2 + wall clock",
            HudLabFrameSpec(
                f28 = 2, distanceMeters = 50, road = "HUD LAB C01",
                f2Counter = true, wallClockTimestamp = true,
            ),
            HudLabObserved.RIGHT,
            listOf(
                HudLabObserved.RIGHT,
                HudLabObserved.LEFT,
                HudLabObserved.STRAIGHT,
                HudLabObserved.NOTHING,
                HudLabObserved.FLASHED,
                HudLabObserved.OTHER,
                HudLabObserved.NOT_REPORTED,
            ),
        ),
        probe(
            "F01", "TEXT · arrival, time, distance before the street",
            probeCard.copy(road = "18:45 · 25 min · 8,4 km | Nádražní"),
            HudLabObserved.TEXT_FULL, textAnswers,
        ),
        probe(
            "F02", "TEXT · the same in plain characters",
            probeCard.copy(road = "18:45 | 25 min | 8.4 km | Nadrazni"),
            HudLabObserved.TEXT_FULL, textAnswers,
        ),
        probe(
            "F03", "TEXT · long street name, 70 characters",
            probeCard.copy(road = "Velmi dlouha ulice pro test delky textu na HUD 0123456789 ABCDEFGHIJKL"),
            HudLabObserved.TEXT_SCROLLS, textAnswers,
        ),
        probe(
            "F04", "TEXT · Czech letters",
            probeCard.copy(road = "Příčná ěščřžýáíéůú ĚŠČŘŽ"),
            HudLabObserved.TEXT_FULL, textAnswers,
        ),
        probe(
            "F05", "TEXT · Russian cue instead of a street",
            probeCard.copy(road = "Поверните налево"),
            HudLabObserved.TEXT_FULL, textAnswers,
        ),
        probe(
            "F06", "DISTANCE · 5 m, below the 11 m byd-hud clamp",
            probeCard.copy(distanceMeters = 5, road = "HUD LAB F06"),
            HudLabObserved.DISTANCE_VISIBLE, distanceAnswers,
        ),
        probe(
            "F07", "DISTANCE · left out (no f9)",
            probeCard.copy(road = "HUD LAB F07", omitDistance = true),
            HudLabObserved.NOW_LABEL, distanceAnswers,
        ),
        extraProbe(
            "F11", "SPEED · f15 speed_limit = 50",
            HudLabObserved.SPEED_SIGN_VISIBLE,
            newElementAnswers(HudLabObserved.SPEED_SIGN_VISIBLE, HudLabObserved.SPEED_NUMBER_VISIBLE),
            HudLabExtraField.Varint(15, 50),
        ),
        probe(
            "F12", "SPEED · f11 and f15 = 80",
            probeCard.copy(
                road = "HUD LAB F12", speedLimit = 80,
                extras = listOf(HudLabExtraField.Varint(15, 80)),
            ),
            HudLabObserved.SPEED_SIGN_VISIBLE,
            newElementAnswers(HudLabObserved.SPEED_SIGN_VISIBLE, HudLabObserved.SPEED_NUMBER_VISIBLE),
        ),
        probe(
            "F13", "SPEED · zone: limit 50 in 200 m for 500 m",
            probeCard.copy(
                road = "HUD LAB F13", speedLimit = 50,
                extras = listOf(
                    HudLabExtraField.Varint(13, 200),
                    HudLabExtraField.Varint(14, 500),
                    HudLabExtraField.Varint(15, 50),
                ),
            ),
            HudLabObserved.SPEED_SIGN_VISIBLE,
            newElementAnswers(HudLabObserved.SPEED_SIGN_VISIBLE, HudLabObserved.SPEED_NUMBER_VISIBLE),
        ),
        probe(
            "F14", "SPEED · current 70 over limit 50",
            probeCard.copy(
                road = "HUD LAB F14", speedLimit = 50,
                extras = listOf(HudLabExtraField.Varint(12, 70), HudLabExtraField.Varint(15, 50)),
            ),
            HudLabObserved.SPEED_SIGN_VISIBLE,
            newElementAnswers(HudLabObserved.SPEED_SIGN_VISIBLE, HudLabObserved.SPEED_NUMBER_VISIBLE),
        ),
        extraProbe(
            "F21", "CAMERA · status 1, 300 m",
            HudLabObserved.CAMERA_VISIBLE, newElementAnswers(HudLabObserved.CAMERA_VISIBLE),
            HudLabExtraField.Varint(17, 1), HudLabExtraField.Varint(18, 300),
        ),
        extraProbe(
            "F22", "CAMERA · status 2, 150 m",
            HudLabObserved.CAMERA_VISIBLE, newElementAnswers(HudLabObserved.CAMERA_VISIBLE),
            HudLabExtraField.Varint(17, 2), HudLabExtraField.Varint(18, 150),
        ),
        extraProbe(
            "F31", "DANGER · sign 1",
            HudLabObserved.DANGER_SIGN_VISIBLE, newElementAnswers(HudLabObserved.DANGER_SIGN_VISIBLE),
            HudLabExtraField.Varint(23, 1),
        ),
        extraProbe(
            "F32", "DANGER · sign 2",
            HudLabObserved.DANGER_SIGN_VISIBLE, newElementAnswers(HudLabObserved.DANGER_SIGN_VISIBLE),
            HudLabExtraField.Varint(23, 2),
        ),
        extraProbe(
            "F33", "DANGER · sign 5",
            HudLabObserved.DANGER_SIGN_VISIBLE, newElementAnswers(HudLabObserved.DANGER_SIGN_VISIBLE),
            HudLabExtraField.Varint(23, 5),
        ),
        extraProbe(
            "F41", "REMAINING · 8400 m and 1500 s to destination",
            HudLabObserved.REMAINING_VISIBLE, newElementAnswers(HudLabObserved.REMAINING_VISIBLE),
            HudLabExtraField.Varint(3, 8_400), HudLabExtraField.Varint(4, 1_500),
        ),
        probe(
            "F42", "ETA · arrival 18:45 and remaining 0:25 as text",
            probeCard.copy(
                road = "HUD LAB F42", etaString = "18:45",
                extras = listOf(HudLabExtraField.Text(27, "0:25")),
            ),
            HudLabObserved.ETA_VISIBLE,
            newElementAnswers(HudLabObserved.ETA_VISIBLE, HudLabObserved.REMAINING_VISIBLE),
        ),
        extraProbe(
            "F43", "DESTINATION · text",
            HudLabObserved.DESTINATION_VISIBLE, newElementAnswers(HudLabObserved.DESTINATION_VISIBLE),
            HudLabExtraField.Text(25, "Nadrazni 12"),
        ),
        extraProbe(
            "F44", "POI · text",
            HudLabObserved.DESTINATION_VISIBLE, newElementAnswers(HudLabObserved.DESTINATION_VISIBLE),
            HudLabExtraField.Text(24, "Shell"),
        ),
        probe(
            "F51", "LANES · 3 lanes in f7/f8, middle straight recommended",
            HudLabFrameSpec(
                f28 = 11, distanceMeters = 200, road = "HUD LAB F51",
                extras = listOf(
                    HudLabExtraField.Varint(5, 3),
                    HudLabExtraField.Packed(7, listOf(1, 0, 3)),
                    HudLabExtraField.Packed(8, listOf(255, 0, 255)),
                ),
            ),
            HudLabObserved.LANES_VISIBLE, newElementAnswers(HudLabObserved.LANES_VISIBLE),
        ),
        probe(
            "F52", "LANES · 3 lanes as f29 text only",
            HudLabFrameSpec(
                f28 = 11, distanceMeters = 200, road = "HUD LAB F52",
                extras = listOf(
                    HudLabExtraField.Varint(5, 3),
                    HudLabExtraField.Text(29, "1,255|0,0|3,255|"),
                ),
            ),
            HudLabObserved.LANES_VISIBLE, newElementAnswers(HudLabObserved.LANES_VISIBLE),
        ),
        probe(
            "F53", "LANES · right turn, both lane encodings",
            HudLabFrameSpec(
                f28 = 2, distanceMeters = 80, road = "HUD LAB F53",
                extras = listOf(
                    HudLabExtraField.Varint(5, 3),
                    HudLabExtraField.Packed(7, listOf(0, 4, 3)),
                    HudLabExtraField.Packed(8, listOf(255, 3, 3)),
                    HudLabExtraField.Text(29, "0,255|4,3|3,3|"),
                ),
            ),
            HudLabObserved.LANES_VISIBLE, newElementAnswers(HudLabObserved.LANES_VISIBLE),
        ),
    )

    fun byId(id: String): HudLabScenario? = (all + search + fields).firstOrNull { it.id == id }

}

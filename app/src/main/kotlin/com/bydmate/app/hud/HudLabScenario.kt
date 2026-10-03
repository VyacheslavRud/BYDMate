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
) {
    val effectiveRenderClass: Int
        get() = renderClass ?: if (includeSpeedSign) 6 else 1

    val fieldManifest: String
        get() = buildList {
            add(if (f2Counter) "f2=counter" else "f2=2")
            add("f6=$effectiveRenderClass")
            if (includeSpeedSign) add("f7=speed_png")
            iconCode?.let { add("f8=0x${it.toString(16)}.png") }
            add("f9=$distanceMeters")
            if (road.isNotEmpty()) add("f10=text")
            if (speedLimit > 0) add("f11=$speedLimit")
            add("f16=2")
            etaString?.let { add("f26=eta") }
            f28?.let { add("f28=$it") }
            val progress = if (totalDistanceMeters > 0) {
                (1.0 - distanceMeters.toDouble() / totalDistanceMeters).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            add("f33=$progress")
        }.joinToString(",")
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
     * C01 asks whether this firmware draws a plain arrow when f2 counts frames, the way the
     * schema names it (`Counter`) and byd-hud sends it. Unique frames could replace the CLEAR that
     * blinks the card after a system overlay. T01-T03 (answered 2026-10-03: the glass redraws a
     * changed arrow without a CLEAR) and the K/P roundabout probes (no roundabout selector; this
     * firmware's f7/f8 are lane arrays, so pictures are refused) stay runnable for comparison.
     */
    val search: List<HudLabScenario> = listOf(
        HudLabScenario(
            id = "C01",
            group = HudLabScenarioGroup.TRANSITION,
            title = "FRAMING · RIGHT at 50 m, counting f2 + wall clock",
            command = null,
            expected = HudLabObserved.RIGHT,
            steps = burst(
                "c01",
                HudLabFrameSpec(
                    f28 = 2,
                    distanceMeters = 50,
                    road = "HUD LAB C01",
                    f2Counter = true,
                    wallClockTimestamp = true,
                ),
            ),
        ),
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

    fun byId(id: String): HudLabScenario? = (all + search).firstOrNull { it.id == id }

}

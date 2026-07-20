package com.bydmate.app.hud

/** Stable groups shown by the dev-only diagnostic matrix. */
enum class HudLabScenarioGroup {
    CONTROL,
    RAW_F28,
    PNG_F8,
    MATCHED,
    CROSSED,
    FULL,
    FIELD_ISOLATION,
    TIMING,
}

/** Only donor fields already used by production guidance are allowed in synthetic frames. */
data class HudLabFrameSpec(
    val f28: Int? = null,
    val iconCode: Int? = null,
    val distanceMeters: Int = 100,
    val road: String = "HUD LAB",
    val etaString: String? = null,
    val totalDistanceMeters: Int = 0,
    val speedLimit: Int = 0,
    val includeSpeedSign: Boolean = false,
) {
    val fieldManifest: String
        get() = buildList {
            add("f2=2")
            add(if (includeSpeedSign) "f6=6" else "f6=1")
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

/**
 * Bounded parked matrix. It intentionally never varies donor constants f2/f16 and never emits
 * fields known to glitch the HUD (f3/f4/f12/f17/f18/f21..f25/f30/f31).
 */
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

    private fun burst(
        label: String,
        frame: HudLabFrameSpec,
        preClear: Boolean = true,
    ): List<HudLabScenarioStep> = buildList {
        if (preClear) add(HudLabScenarioStep.Clear(attempts = 3))
        add(send(label, frame, gapBeforeMs = if (preClear) CLEAR_GAP_MS else 0L))
    }

    private fun directionFrame(command: HudLabCommand, png: Boolean, f28: Boolean) =
        HudLabFrameSpec(
            f28 = command.rawF28.takeIf { f28 },
            iconCode = command.gaodeCode.takeIf { png },
        )

    val all: List<HudLabScenario> = listOf(
        HudLabScenario(
            "W01", HudLabScenarioGroup.CONTROL, "Canonical CLEAR", null,
            HudLabObserved.NOTHING, listOf(HudLabScenarioStep.Clear(attempts = 3)),
        ),
        HudLabScenario(
            "W02", HudLabScenarioGroup.CONTROL, "Scaffold SINGLE", null,
            HudLabObserved.NOTHING,
            listOf(send("scaffold", HudLabFrameSpec(), repeat = 1, cadenceMs = 0L)),
        ),
        HudLabScenario(
            "W03", HudLabScenarioGroup.CONTROL, "Scaffold BURST", null,
            HudLabObserved.NOTHING, burst("scaffold", HudLabFrameSpec()),
        ),

        HudLabScenario("W04", HudLabScenarioGroup.RAW_F28, "f28=1 STRAIGHT", HudLabCommand.STRAIGHT,
            HudLabObserved.STRAIGHT, burst("f28_straight", directionFrame(HudLabCommand.STRAIGHT, false, true))),
        HudLabScenario("W05", HudLabScenarioGroup.RAW_F28, "f28=2 RIGHT", HudLabCommand.RIGHT,
            HudLabObserved.RIGHT, burst("f28_right", directionFrame(HudLabCommand.RIGHT, false, true))),
        HudLabScenario("W06", HudLabScenarioGroup.RAW_F28, "f28=3 LEFT", HudLabCommand.LEFT,
            HudLabObserved.LEFT, burst("f28_left", directionFrame(HudLabCommand.LEFT, false, true))),
        HudLabScenario("W07", HudLabScenarioGroup.RAW_F28, "f28=9 UTURN", HudLabCommand.UTURN,
            HudLabObserved.UTURN, burst("f28_uturn", directionFrame(HudLabCommand.UTURN, false, true))),

        HudLabScenario("W08", HudLabScenarioGroup.PNG_F8, "f8=0x0 BLANK donor PNG", null,
            HudLabObserved.NOTHING, burst("f8_blank", HudLabFrameSpec(iconCode = 0))),
        HudLabScenario("W09", HudLabScenarioGroup.PNG_F8, "f8=0x1 LEFT only", HudLabCommand.LEFT,
            HudLabObserved.LEFT, burst("f8_left", directionFrame(HudLabCommand.LEFT, true, false))),
        HudLabScenario("W10", HudLabScenarioGroup.PNG_F8, "f8=0x2 RIGHT only", HudLabCommand.RIGHT,
            HudLabObserved.RIGHT, burst("f8_right", directionFrame(HudLabCommand.RIGHT, true, false))),
        HudLabScenario("W11", HudLabScenarioGroup.PNG_F8, "f8=0xb STRAIGHT only", HudLabCommand.STRAIGHT,
            HudLabObserved.STRAIGHT, burst("f8_straight", directionFrame(HudLabCommand.STRAIGHT, true, false))),
        HudLabScenario("W12", HudLabScenarioGroup.PNG_F8, "f8=0x9 UTURN only", HudLabCommand.UTURN,
            HudLabObserved.UTURN, burst("f8_uturn", directionFrame(HudLabCommand.UTURN, true, false))),

        HudLabScenario("W13", HudLabScenarioGroup.MATCHED, "LEFT f8+f28", HudLabCommand.LEFT,
            HudLabObserved.LEFT, burst("matched_left", directionFrame(HudLabCommand.LEFT, true, true))),
        HudLabScenario("W14", HudLabScenarioGroup.MATCHED, "RIGHT f8+f28", HudLabCommand.RIGHT,
            HudLabObserved.RIGHT, burst("matched_right", directionFrame(HudLabCommand.RIGHT, true, true))),
        HudLabScenario("W15", HudLabScenarioGroup.MATCHED, "STRAIGHT f8+f28", HudLabCommand.STRAIGHT,
            HudLabObserved.STRAIGHT, burst("matched_straight", directionFrame(HudLabCommand.STRAIGHT, true, true))),
        HudLabScenario("W16", HudLabScenarioGroup.MATCHED, "UTURN f8+f28", HudLabCommand.UTURN,
            HudLabObserved.UTURN, burst("matched_uturn", directionFrame(HudLabCommand.UTURN, true, true))),

        HudLabScenario(
            "W17", HudLabScenarioGroup.CROSSED, "LEFT PNG + RIGHT f28", HudLabCommand.LEFT,
            HudLabObserved.LEFT,
            burst("cross_left_png_right_f28", HudLabFrameSpec(f28 = 2, iconCode = 1)),
        ),
        HudLabScenario(
            "W18", HudLabScenarioGroup.CROSSED, "RIGHT PNG + LEFT f28", HudLabCommand.RIGHT,
            HudLabObserved.RIGHT,
            burst("cross_right_png_left_f28", HudLabFrameSpec(f28 = 3, iconCode = 2)),
        ),

        HudLabScenario(
            "W19", HudLabScenarioGroup.FULL, "Info: distance + road + ETA", null,
            HudLabObserved.INFO_VISIBLE,
            burst("info", HudLabFrameSpec(distanceMeters = 500, etaString = "12:34")),
        ),
        HudLabScenario(
            "W20", HudLabScenarioGroup.FULL, "Info + progress 50%", null,
            HudLabObserved.INFO_VISIBLE,
            burst(
                "progress_50",
                HudLabFrameSpec(distanceMeters = 500, etaString = "12:34", totalDistanceMeters = 1_000),
            ),
        ),
        HudLabScenario(
            "W21", HudLabScenarioGroup.FULL, "Speed sign 60 only", null,
            HudLabObserved.SPEED_SIGN_VISIBLE,
            burst("speed_sign", HudLabFrameSpec(speedLimit = 60, includeSpeedSign = true)),
        ),
        HudLabScenario(
            "W22", HudLabScenarioGroup.FULL, "Full donor LEFT", HudLabCommand.LEFT,
            HudLabObserved.LEFT,
            burst(
                "full_left",
                HudLabFrameSpec(
                    f28 = 3, iconCode = 1, distanceMeters = 500, etaString = "12:34",
                    totalDistanceMeters = 1_000, speedLimit = 60, includeSpeedSign = true,
                ),
            ),
        ),
        HudLabScenario(
            "W23", HudLabScenarioGroup.FULL, "Full donor RIGHT", HudLabCommand.RIGHT,
            HudLabObserved.RIGHT,
            burst(
                "full_right",
                HudLabFrameSpec(
                    f28 = 2, iconCode = 2, distanceMeters = 500, etaString = "12:34",
                    totalDistanceMeters = 1_000, speedLimit = 60, includeSpeedSign = true,
                ),
            ),
        ),
        HudLabScenario(
            "W24", HudLabScenarioGroup.FULL, "LEFT burst -> RIGHT burst (no CLEAR)", HudLabCommand.RIGHT,
            HudLabObserved.LEFT_THEN_RIGHT,
            listOf(
                HudLabScenarioStep.Clear(attempts = 3),
                send("transition_left", directionFrame(HudLabCommand.LEFT, true, true), gapBeforeMs = CLEAR_GAP_MS),
                send("transition_right", directionFrame(HudLabCommand.RIGHT, true, true), gapBeforeMs = BURST_CADENCE_MS),
            ),
        ),
        HudLabScenario(
            "W25", HudLabScenarioGroup.FULL, "RIGHT -> CLEAR -> RIGHT redraw", HudLabCommand.RIGHT,
            HudLabObserved.RIGHT_CLEARED_AND_REDRAWN,
            listOf(
                HudLabScenarioStep.Clear(attempts = 3),
                send("redraw_before", directionFrame(HudLabCommand.RIGHT, true, true), gapBeforeMs = CLEAR_GAP_MS),
                HudLabScenarioStep.Clear(attempts = 3, gapBeforeMs = BURST_CADENCE_MS),
                send("redraw_after", directionFrame(HudLabCommand.RIGHT, true, true), gapBeforeMs = CLEAR_GAP_MS),
            ),
        ),

        // Field-isolation pairs keep the donor scaffold fixed and vary one field at a time. W26
        // is the distance baseline for W29; W30/W31 separate numeric f11 from the f7 PNG flag.
        HudLabScenario(
            "W26", HudLabScenarioGroup.FIELD_ISOLATION, "f9 distance 500 only", null,
            HudLabObserved.DISTANCE_VISIBLE,
            burst("field_distance", HudLabFrameSpec(distanceMeters = 500, road = "")),
        ),
        HudLabScenario(
            "W27", HudLabScenarioGroup.FIELD_ISOLATION, "f10 road text only", null,
            HudLabObserved.ROAD_VISIBLE,
            burst("field_road", HudLabFrameSpec(distanceMeters = 0, road = "HUD LAB ROAD")),
        ),
        HudLabScenario(
            "W28", HudLabScenarioGroup.FIELD_ISOLATION, "f26 ETA 12:34 only", null,
            HudLabObserved.ETA_VISIBLE,
            burst(
                "field_eta",
                HudLabFrameSpec(distanceMeters = 0, road = "", etaString = "12:34"),
            ),
        ),
        HudLabScenario(
            "W29", HudLabScenarioGroup.FIELD_ISOLATION, "f33 progress 50% vs W26", null,
            HudLabObserved.PROGRESS_VISIBLE,
            burst(
                "field_progress",
                HudLabFrameSpec(distanceMeters = 500, road = "", totalDistanceMeters = 1_000),
            ),
        ),
        HudLabScenario(
            "W30", HudLabScenarioGroup.FIELD_ISOLATION, "f11 speed number 60, no PNG", null,
            HudLabObserved.SPEED_NUMBER_VISIBLE,
            burst(
                "field_speed_number",
                HudLabFrameSpec(distanceMeters = 0, road = "", speedLimit = 60),
            ),
        ),
        HudLabScenario(
            "W31", HudLabScenarioGroup.FIELD_ISOLATION, "f7 PNG + f11 speed 60", null,
            HudLabObserved.SPEED_SIGN_VISIBLE,
            burst(
                "field_speed_png",
                HudLabFrameSpec(
                    distanceMeters = 0,
                    road = "",
                    speedLimit = 60,
                    includeSpeedSign = true,
                ),
            ),
        ),

        // Cadence isolates whether this gateway needs a repeated live stream before rendering.
        HudLabScenario(
            "W32", HudLabScenarioGroup.TIMING, "f28 RIGHT single", HudLabCommand.RIGHT,
            HudLabObserved.RIGHT,
            listOf(
                HudLabScenarioStep.Clear(attempts = 3),
                send(
                    "timing_f28_single",
                    directionFrame(HudLabCommand.RIGHT, png = false, f28 = true),
                    repeat = 1,
                    cadenceMs = 0L,
                    gapBeforeMs = CLEAR_GAP_MS,
                ),
            ),
        ),
        HudLabScenario(
            "W33", HudLabScenarioGroup.TIMING, "f8 RIGHT single", HudLabCommand.RIGHT,
            HudLabObserved.RIGHT,
            listOf(
                HudLabScenarioStep.Clear(attempts = 3),
                send(
                    "timing_f8_single",
                    directionFrame(HudLabCommand.RIGHT, png = true, f28 = false),
                    repeat = 1,
                    cadenceMs = 0L,
                    gapBeforeMs = CLEAR_GAP_MS,
                ),
            ),
        ),
        HudLabScenario(
            "W34", HudLabScenarioGroup.TIMING, "f8+f28 RIGHT single", HudLabCommand.RIGHT,
            HudLabObserved.RIGHT,
            listOf(
                HudLabScenarioStep.Clear(attempts = 3),
                send(
                    "timing_matched_single",
                    directionFrame(HudLabCommand.RIGHT, png = true, f28 = true),
                    repeat = 1,
                    cadenceMs = 0L,
                    gapBeforeMs = CLEAR_GAP_MS,
                ),
            ),
        ),
        HudLabScenario(
            "W35", HudLabScenarioGroup.TIMING, "f8+f28 RIGHT 10x @100ms", HudLabCommand.RIGHT,
            HudLabObserved.RIGHT,
            listOf(
                HudLabScenarioStep.Clear(attempts = 3),
                send(
                    "timing_matched_fast",
                    directionFrame(HudLabCommand.RIGHT, png = true, f28 = true),
                    repeat = 10,
                    cadenceMs = 100L,
                    gapBeforeMs = CLEAR_GAP_MS,
                ),
            ),
        ),
        HudLabScenario(
            "W36", HudLabScenarioGroup.TIMING, "f8+f28 RIGHT 6x @500ms", HudLabCommand.RIGHT,
            HudLabObserved.RIGHT,
            listOf(
                HudLabScenarioStep.Clear(attempts = 3),
                send(
                    "timing_matched_slow",
                    directionFrame(HudLabCommand.RIGHT, png = true, f28 = true),
                    repeat = 6,
                    cadenceMs = 500L,
                    gapBeforeMs = CLEAR_GAP_MS,
                ),
            ),
        ),
    )

    fun byId(id: String): HudLabScenario? = all.firstOrNull { it.id == id }
}

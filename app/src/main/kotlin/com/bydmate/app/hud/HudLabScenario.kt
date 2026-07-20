package com.bydmate.app.hud

/** Stable groups shared by the focused research and repeatable calibration catalogs. */
enum class HudLabScenarioGroup {
    SPEED_RESEARCH,
    UTURN,
    ROUNDABOUT,
    SPEED_LIMIT,
    CONTROL,
}

/** Only donor fields already used by production guidance are allowed in synthetic frames. */
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
) {
    val effectiveRenderClass: Int
        get() = renderClass ?: if (includeSpeedSign) 6 else 1

    val fieldManifest: String
        get() = buildList {
            add("f2=2")
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

/** Separate catalogs for the current question and the six useful repeat-calibration scenarios. */
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

    private fun speedResearch(
        id: String,
        title: String,
        frame: HudLabFrameSpec,
        expected: HudLabObserved,
        repeat: Int = BURST_COUNT,
        cadenceMs: Long = BURST_CADENCE_MS,
    ) = HudLabScenario(
        id = id,
        group = HudLabScenarioGroup.SPEED_RESEARCH,
        title = title,
        command = null,
        expected = expected,
        steps = listOf(
            HudLabScenarioStep.Clear(attempts = 3),
            send(
                label = id.lowercase(),
                frame = frame,
                repeat = repeat,
                cadenceMs = cadenceMs,
                gapBeforeMs = CLEAR_GAP_MS,
            ),
        ),
    )

    /**
     * Follow-up matrix derived from the N17/N18 result: f11 rendered a number only when a confirmed
     * maneuver f28 was present. It isolates f6, speed value, distance, route context, an arrow-free
     * f28=0 candidate and delivery cadence. Rejected PNG fields stay excluded.
     */
    val current: List<HudLabScenario> = listOf(
        speedResearch(
            "X01", "MINIMAL RIGHT · f6=1 · f11=50",
            HudLabFrameSpec(f28 = 2, distanceMeters = 50, road = "", speedLimit = 50),
            HudLabObserved.SPEED_NUMBER_WITH_MANEUVER_VISIBLE,
        ),
        speedResearch(
            "X02", "MINIMAL LEFT · f6=1 · f11=50",
            HudLabFrameSpec(f28 = 3, distanceMeters = 50, road = "", speedLimit = 50),
            HudLabObserved.SPEED_NUMBER_WITH_MANEUVER_VISIBLE,
        ),
        speedResearch(
            "X03", "VALUE RIGHT · f11=80 · f6=6",
            HudLabFrameSpec(
                renderClass = 6,
                f28 = 2,
                distanceMeters = 50,
                road = "",
                speedLimit = 80,
            ),
            HudLabObserved.SPEED_NUMBER_WITH_MANEUVER_VISIBLE,
        ),
        speedResearch(
            "X04", "VALUE LEFT · f11=80 · f6=6",
            HudLabFrameSpec(
                renderClass = 6,
                f28 = 3,
                distanceMeters = 50,
                road = "",
                speedLimit = 80,
            ),
            HudLabObserved.SPEED_NUMBER_WITH_MANEUVER_VISIBLE,
        ),
        speedResearch(
            "X05", "DISTANCE RIGHT · f9=20",
            HudLabFrameSpec(
                renderClass = 6,
                f28 = 2,
                distanceMeters = 20,
                road = "",
                speedLimit = 50,
            ),
            HudLabObserved.SPEED_NUMBER_WITH_MANEUVER_VISIBLE,
        ),
        speedResearch(
            "X06", "DISTANCE RIGHT · f9=100",
            HudLabFrameSpec(
                renderClass = 6,
                f28 = 2,
                distanceMeters = 100,
                road = "",
                speedLimit = 50,
            ),
            HudLabObserved.SPEED_NUMBER_WITH_MANEUVER_VISIBLE,
        ),
        speedResearch(
            "X07", "DISTANCE LEFT · f9=20",
            HudLabFrameSpec(
                renderClass = 6,
                f28 = 3,
                distanceMeters = 20,
                road = "",
                speedLimit = 50,
            ),
            HudLabObserved.SPEED_NUMBER_WITH_MANEUVER_VISIBLE,
        ),
        speedResearch(
            "X08", "DISTANCE LEFT · f9=100",
            HudLabFrameSpec(
                renderClass = 6,
                f28 = 3,
                distanceMeters = 100,
                road = "",
                speedLimit = 50,
            ),
            HudLabObserved.SPEED_NUMBER_WITH_MANEUVER_VISIBLE,
        ),
        speedResearch(
            "X09", "ROAD RIGHT · f10 text",
            HudLabFrameSpec(
                renderClass = 6,
                f28 = 2,
                distanceMeters = 50,
                road = "HUD LAB SPEED",
                speedLimit = 50,
            ),
            HudLabObserved.SPEED_MANEUVER_ROAD_VISIBLE,
        ),
        speedResearch(
            "X10", "ETA RIGHT · f26=12:34",
            HudLabFrameSpec(
                renderClass = 6,
                f28 = 2,
                distanceMeters = 50,
                road = "",
                etaString = "12:34",
                speedLimit = 50,
            ),
            HudLabObserved.SPEED_MANEUVER_ETA_VISIBLE,
        ),
        speedResearch(
            "X11", "PROGRESS RIGHT · f33=0.5",
            HudLabFrameSpec(
                renderClass = 6,
                f28 = 2,
                distanceMeters = 50,
                road = "",
                totalDistanceMeters = 100,
                speedLimit = 50,
            ),
            HudLabObserved.SPEED_MANEUVER_PROGRESS_VISIBLE,
        ),
        speedResearch(
            "X12", "FULL SCALAR LEFT · road + ETA + progress",
            HudLabFrameSpec(
                renderClass = 6,
                f28 = 3,
                distanceMeters = 50,
                road = "HUD LAB FULL",
                etaString = "12:34",
                totalDistanceMeters = 100,
                speedLimit = 50,
            ),
            HudLabObserved.FULL_SCALAR_VISIBLE,
        ),
        speedResearch(
            "X13", "ARROW-FREE CANDIDATE · f28=0",
            HudLabFrameSpec(
                renderClass = 6,
                f28 = 0,
                distanceMeters = 50,
                road = "",
                speedLimit = 50,
            ),
            HudLabObserved.SPEED_NUMBER_VISIBLE,
        ),
        speedResearch(
            "X14", "UTURN COEXIST · f28=9",
            HudLabFrameSpec(
                renderClass = 6,
                f28 = 9,
                distanceMeters = 50,
                road = "",
                speedLimit = 50,
            ),
            HudLabObserved.SPEED_NUMBER_WITH_MANEUVER_VISIBLE,
        ),
        speedResearch(
            "X15", "CADENCE · RIGHT single packet",
            HudLabFrameSpec(
                renderClass = 6,
                f28 = 2,
                distanceMeters = 50,
                road = "",
                speedLimit = 50,
            ),
            HudLabObserved.SPEED_NUMBER_WITH_MANEUVER_VISIBLE,
            repeat = 1,
            cadenceMs = 0L,
        ),
        speedResearch(
            "X16", "CADENCE · RIGHT 6x @500ms",
            HudLabFrameSpec(
                renderClass = 6,
                f28 = 2,
                distanceMeters = 50,
                road = "",
                speedLimit = 50,
            ),
            HudLabObserved.SPEED_NUMBER_WITH_MANEUVER_VISIBLE,
            repeat = 6,
            cadenceMs = 500L,
        ),
        speedResearch(
            "X17", "DEPENDENCY CONTROL · RIGHT without f11",
            HudLabFrameSpec(
                renderClass = 6,
                f28 = 2,
                distanceMeters = 50,
                road = "",
            ),
            HudLabObserved.RIGHT,
        ),
        speedResearch(
            "X18", "DEPENDENCY CONTROL · LEFT without f11",
            HudLabFrameSpec(
                renderClass = 6,
                f28 = 3,
                distanceMeters = 50,
                road = "",
            ),
            HudLabObserved.LEFT,
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

    private fun confirmedCoexistence(
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

    /** Previous calibration plus the two visually confirmed Sea Lion speed-number scenarios. */
    val calibration: List<HudLabScenario> = listOf(
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
        confirmedCoexistence("N17", "CONFIRMED · RIGHT + speed 50", 2),
        confirmedCoexistence("N18", "CONFIRMED · LEFT + speed 50", 3),
    )

    val all: List<HudLabScenario> = current + calibration

    fun byId(id: String): HudLabScenario? = all.firstOrNull { it.id == id }
}

package com.bydmate.app.hud

/** Stable groups shared by the Sea Lion smoke tests and compatibility calibration catalog. */
enum class HudLabScenarioGroup {
    SEA_LION_CONFIRMED,
    SEA_LION_EXTENDED,
    F28_EXPLORER,
    UTURN,
    ROUNDABOUT,
    SPEED_LIMIT,
    CONTROL,
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

    private fun extendedSeaLion(
        id: String,
        title: String,
        frame: HudLabFrameSpec,
        expected: HudLabObserved,
    ) = HudLabScenario(
        id = id,
        group = HudLabScenarioGroup.SEA_LION_EXTENDED,
        title = title,
        command = null,
        expected = expected,
        steps = burst(id.lowercase(), frame),
    )

    /**
     * Scalar extensions present in the donor SOME/IP frame but deliberately excluded from
     * production until this exact Sea Lion firmware confirms them. Each scenario changes one
     * bounded variable over the confirmed RIGHT-at-50-m baseline; HX04 combines them only after
     * the isolated checks, and HX05 compares the donor render class without either PNG field. The
     * HX namespace avoids reusing retired X01-X18 journal identifiers from older Dev builds.
     */
    val extended: List<HudLabScenario> = listOf(
        extendedSeaLion(
            "HX01", "SPEED · f11=50, f6=1",
            HudLabFrameSpec(f28 = 2, distanceMeters = 50, road = "", speedLimit = 50),
            HudLabObserved.SPEED_NUMBER_WITH_MANEUVER_VISIBLE,
        ),
        extendedSeaLion(
            "HX02", "ETA · f26=12:34",
            HudLabFrameSpec(f28 = 2, distanceMeters = 50, road = "", etaString = "12:34"),
            HudLabObserved.ETA_VISIBLE,
        ),
        extendedSeaLion(
            "HX03", "PROGRESS · f33=0.5",
            HudLabFrameSpec(
                f28 = 2,
                distanceMeters = 50,
                road = "",
                totalDistanceMeters = 100,
            ),
            HudLabObserved.PROGRESS_VISIBLE,
        ),
        extendedSeaLion(
            "HX04", "FULL SCALAR · road + speed + ETA + progress",
            HudLabFrameSpec(
                f28 = 3,
                distanceMeters = 50,
                road = "HUD LAB FULL",
                etaString = "12:34",
                totalDistanceMeters = 100,
                speedLimit = 50,
            ),
            HudLabObserved.FULL_SCALAR_VISIBLE,
        ),
        extendedSeaLion(
            "HX05", "RENDER CLASS · f6=6 + f11=50",
            HudLabFrameSpec(
                renderClass = 6,
                f28 = 2,
                distanceMeters = 50,
                road = "",
                speedLimit = 50,
            ),
            HudLabObserved.SPEED_NUMBER_WITH_MANEUVER_VISIBLE,
        ),
    )

    /**
     * Safe unknown-symbol explorer. It reuses the minimal confirmed Sea Lion frame and changes
     * only f28. Three sends are enough for a parked visual check while keeping bus traffic below
     * the normal ten-packet smoke-test burst.
     */
    val explorer: List<HudLabScenario> = HudF28ExplorerCatalog.candidates.map { rawF28 ->
        val hex = rawF28.toString(16).uppercase().padStart(2, '0')
        HudLabScenario(
            id = HudF28ExplorerCatalog.scenarioId(rawF28),
            group = HudLabScenarioGroup.F28_EXPLORER,
            title = "UNKNOWN f28=0x$hex ($rawF28)",
            command = null,
            expected = HudLabObserved.NAMED_INDICATOR,
            steps = listOf(
                HudLabScenarioStep.Clear(attempts = 3),
                send(
                    label = "explore_f28_$rawF28",
                    frame = HudLabFrameSpec(f28 = rawF28, distanceMeters = 50, road = ""),
                    repeat = 3,
                    gapBeforeMs = CLEAR_GAP_MS,
                ),
            ),
        )
    }

    val all: List<HudLabScenario> = confirmed + extended + compatibility + explorer

    fun byId(id: String): HudLabScenario? = all.firstOrNull { it.id == id }

    fun isExplorerScenario(id: String?): Boolean = id != null && explorer.any { it.id == id }
}

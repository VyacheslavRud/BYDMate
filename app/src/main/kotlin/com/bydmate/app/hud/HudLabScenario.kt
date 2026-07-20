package com.bydmate.app.hud

/** Compact calibration targets; CONTROL is reserved for emergency clear journal entries. */
enum class HudLabScenarioGroup {
    UTURN,
    ROUNDABOUT,
    SPEED_LIMIT,
    CONTROL,
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
 * Compact parked calibration for the remaining Sea Lion 07 unknowns.
 *
 * LEFT/RIGHT and the firmware distance threshold are already confirmed and belong to production,
 * not this lab. Each scenario below changes one candidate field while retaining the accepted plain
 * frame and live 300 ms cadence. No rejected PNG field is reintroduced.
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

    private fun burst(label: String, frame: HudLabFrameSpec): List<HudLabScenarioStep> = listOf(
        HudLabScenarioStep.Clear(attempts = 3),
        send(label, frame, gapBeforeMs = CLEAR_GAP_MS),
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

    val all: List<HudLabScenario> = listOf(
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
    )

    fun byId(id: String): HudLabScenario? = all.firstOrNull { it.id == id }
}

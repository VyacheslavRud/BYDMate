package com.bydmate.app.navdata

/**
 * Debounces pixel readings of Waze's maneuver icon before they reach the route state.
 *
 * One screenshot can catch Waze mid-animation, an overlay over the icon or the previous turn's
 * icon right after the distance jumped to the next maneuver. A single such reading used to flip
 * the windshield arrow to the wrong side and blink the card twice (wrong arrow, then back).
 * A direction now changes only after [REQUIRED_AGREEMENT] consecutive identical readings, and a
 * direction the reader stops recognizing is dropped after as many unrecognized readings, so a
 * passed turn cannot stay on the glass while Waze shows an icon the classifier cannot name.
 *
 * Readings further apart than [MAX_READING_GAP_MS] do not count as consecutive.
 */
internal class VisualManeuverFilter {

    sealed interface Decision {
        /** The reading confirms what is already shown; only the route lease is renewed. */
        data object Confirmed : Decision

        /** A new direction is pending; ask for another reading soon. */
        data class Pending(val candidate: Int) : Decision

        /** Two consecutive readings agree on a new direction. */
        data class Apply(val maneuverGaode: Int) : Decision

        /** The shown direction is no longer recognizable on Waze's icon. */
        data object Clear : Decision
    }

    private var candidate = 0
    private var candidateCount = 0
    private var candidateAtMs = 0L
    private var unrecognizedCount = 0
    private var unrecognizedAtMs = 0L

    /**
     * [reading] is the classified gaode code, or 0 when the icon was captured but not recognized.
     * [current] is the maneuver the route state shows right now (0 = none).
     */
    @Synchronized
    fun onReading(reading: Int, current: Int, nowMs: Long): Decision {
        if (reading <= 0) {
            candidate = 0
            candidateCount = 0
            unrecognizedCount = if (consecutive(unrecognizedAtMs, nowMs)) unrecognizedCount + 1 else 1
            unrecognizedAtMs = nowMs
            if (current <= 0) return Decision.Confirmed
            return if (unrecognizedCount >= REQUIRED_AGREEMENT) {
                unrecognizedCount = 0
                Decision.Clear
            } else {
                Decision.Pending(0)
            }
        }
        unrecognizedCount = 0
        if (reading == current) {
            candidate = 0
            candidateCount = 0
            return Decision.Confirmed
        }
        candidateCount = if (reading == candidate && consecutive(candidateAtMs, nowMs)) {
            candidateCount + 1
        } else {
            1
        }
        candidate = reading
        candidateAtMs = nowMs
        if (candidateCount < REQUIRED_AGREEMENT) return Decision.Pending(reading)
        candidate = 0
        candidateCount = 0
        return Decision.Apply(reading)
    }

    @Synchronized
    fun reset() {
        candidate = 0
        candidateCount = 0
        candidateAtMs = 0L
        unrecognizedCount = 0
        unrecognizedAtMs = 0L
    }

    private fun consecutive(previousAtMs: Long, nowMs: Long): Boolean =
        previousAtMs > 0L && nowMs >= previousAtMs && nowMs - previousAtMs <= MAX_READING_GAP_MS

    companion object {
        const val REQUIRED_AGREEMENT = 2
        const val MAX_READING_GAP_MS = 5_000L
    }
}

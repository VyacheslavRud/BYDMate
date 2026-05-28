package com.bydmate.app.data.loop

enum class LoopState { DRIVE, CHARGE, PARKED, IDLE }

data class CadenceConfig(
    val driveMs: Long,
    val chargeMs: Long,
    val parkedMs: Long,
    val idleMs: Long,
) {
    fun intervalFor(state: LoopState): Long = when (state) {
        LoopState.DRIVE -> driveMs
        LoopState.CHARGE -> chargeMs
        LoopState.PARKED -> parkedMs
        LoopState.IDLE -> idleMs
    }

    companion object {
        const val MAX_POLL_INTERVAL_MS = 60_000L
        fun default() = CadenceConfig(
            driveMs = 1_000L,
            chargeMs = 5_000L,
            parkedMs = 5_000L,
            idleMs = 30_000L,
        )
    }
}

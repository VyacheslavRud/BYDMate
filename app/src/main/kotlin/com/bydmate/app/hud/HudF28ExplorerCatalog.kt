package com.bydmate.app.hud

/**
 * Audited selector values exposed by the bundled donor navigation asset inventory.
 *
 * The explorer deliberately changes only protobuf field 28. Values 0x05 and 0x06 have no donor
 * asset and values outside this inventory are never accepted by the parked laboratory builder.
 */
object HudF28ExplorerCatalog {
    val donorValues: Set<Int> = ((0..4) + (7..49)).toSet()

    /** Values already covered by confirmed or retained compatibility scenarios. */
    val alreadyTestedValues: Set<Int> = setOf(0, 1, 2, 3, 9, 13, 24)

    val candidates: List<Int> = (donorValues - alreadyTestedValues).sorted()

    fun isCandidate(value: Int): Boolean = value in candidates

    fun scenarioId(value: Int): String {
        require(isCandidate(value)) { "unsupported HUD f28 explorer value=$value" }
        return "E${value.toString(16).uppercase().padStart(2, '0')}"
    }
}

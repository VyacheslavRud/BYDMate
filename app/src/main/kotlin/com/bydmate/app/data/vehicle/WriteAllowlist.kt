package com.bydmate.app.data.vehicle

/**
 * Hardcoded action_name → write address map. Phase 2 Group C populates
 * the PRODUCTION instance from Phase 2c Round 1 + Round 2 probe results.
 *
 * Every entry MUST sit on a comfort-tier dev namespace. The invariant test
 * (WriteAllowlistTest) refuses to compile a release against any entry
 * targeting dev ∈ {1004, 1006, 1007, 1009, 1011, 1012, 1013, 1014, 1016, 1023, 1032}.
 *
 * Banned namespace reference:
 * - 1004 = light enums (firmware-protected)
 * - 1006 = drive mode
 * - 1007 = seatbelt sensor
 * - 1009 = charging gun state
 * - 1011 = gear
 * - 1012 = engine power
 * - 1013 = vehicle speed
 * - 1014 = BMS statistics
 * - 1016 = tire pressure
 * - 1023 = global powerState
 * - 1032 = door lock state-of-truth
 */
data class WriteEntry(
    val actionName: String,
    val dev: Int,
    val writeFid: Int,
    val readbackFid: Int?,
    val valueMin: Int,
    val valueMax: Int,
    val category: String,
)

class WriteAllowlist(private val map: Map<String, WriteEntry>) {
    val size: Int get() = map.size
    fun find(actionName: String): WriteEntry? = map[actionName]
    fun allEntries(): Collection<WriteEntry> = map.values

    companion object {
        val EMPTY: WriteAllowlist = WriteAllowlist(emptyMap())
        // Populated in Group C task C.1 from Phase 2c results.
        val PRODUCTION: WriteAllowlist = WriteAllowlist(emptyMap())
    }
}

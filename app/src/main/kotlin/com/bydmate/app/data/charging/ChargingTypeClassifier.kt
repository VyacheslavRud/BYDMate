package com.bydmate.app.data.charging

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Classifies a charging session as AC or DC.
 *
 * Preferred path (live): use the `gun_state` value captured at handshake.
 * Fallback (catch-up): use a conservative kWh / hours heuristic. The target Chinese-market
 * Sea Lion's onboard AC charger stays below the 15 kW boundary; a slow or heavily tapered DC
 * session can still be misclassified, so the directly observed gun state always wins.
 *
 * The user can always override via the optional finalize prompt (Phase 3).
 */
@Singleton
class ChargingTypeClassifier @Inject constructor() {

    companion object {
        /** Heuristic only; not a claimed charger limit or a CCS/GB/T protocol boundary. */
        const val DC_AVG_POWER_KW_THRESHOLD = 15.0
    }

    /**
     * Maps the raw gun_state autoservice value to "AC"/"DC", or null if
     * the gun is disconnected (state 1) or unknown.
     *   1 = NONE
     *   2 = AC
     *   3 = DC
     *   4 = GB_DC (treat as DC for UI/tariff purposes)
     */
    fun fromGunState(gunState: Int?): String? = when (gunState) {
        ChargeGunState.AC -> "AC"
        ChargeGunState.DC, ChargeGunState.AC_DC -> "DC"
        else -> null
    }

    /**
     * Heuristic for catch-up paths where gun_state is no longer available.
     * Returns "DC" if avg power > threshold; "AC" otherwise (and as a safe
     * default when inputs are degenerate — picks the cheaper tariff).
     */
    fun heuristicByPower(kwhCharged: Double, hours: Double): String {
        if (kwhCharged <= 0.0 || hours <= 0.0) return "AC"
        val avgKw = kwhCharged / hours
        return if (avgKw > DC_AVG_POWER_KW_THRESHOLD) "DC" else "AC"
    }

    /**
     * Live path: classify by the directly observed motor power magnitude
     * (DiPars 发动机功率, kW; negative when energy flows into battery).
     * Cleaner than the kwh/hours heuristic — works correctly for short
     * sessions where the kwh/hours heuristic's elapsed-time fallback
     * underestimates the true power by an order of magnitude.
     */
    fun fromObservedPowerKw(observedKwAbs: Double?): String? {
        if (observedKwAbs == null || observedKwAbs <= 0.0) return null
        return if (observedKwAbs > DC_AVG_POWER_KW_THRESHOLD) "DC" else "AC"
    }
}

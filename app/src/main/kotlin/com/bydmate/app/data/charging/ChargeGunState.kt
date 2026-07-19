package com.bydmate.app.data.charging

/**
 * Canonical meaning of autoservice FID 876609586 (gun-connect state).
 *
 * V2L/VTOL is deliberately kept separate from charging: in that mode the traction battery is
 * exporting energy to an external load. Treating it as an input charger creates phantom charge
 * rows and tells route planners that the car is receiving DC power.
 */
object ChargeGunState {
    const val NONE = 1
    const val AC = 2
    const val DC = 3
    const val AC_DC = 4
    const val V2L = 5

    fun isKnown(value: Int?): Boolean = value in NONE..V2L

    fun isCharging(value: Int?): Boolean = value in AC..AC_DC

    fun isDcCharging(value: Int?): Boolean = value == DC || value == AC_DC

    fun isV2l(value: Int?): Boolean = value == V2L

    fun isExternalConnectorPresent(value: Int?): Boolean = value in AC..V2L
}

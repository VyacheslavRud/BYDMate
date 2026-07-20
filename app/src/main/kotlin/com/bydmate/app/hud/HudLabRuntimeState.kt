package com.bydmate.app.hud

import android.annotation.SuppressLint
import android.content.Context

/**
 * Process-death-safe ownership marker for the shared factory HUD topic.
 *
 * It is committed before a synthetic mutation and cleared only after a confirmed zero CLEAR (or
 * a confirmed production guidance frame that supersedes it). A stale true is safe: startup emits
 * an extra bounded clear. A stale false could leave a calibration arrow on glass, so writes fail
 * closed before a lab SEND.
 */
internal object HudLabRuntimeState {
    private const val PREFS_NAME = "hud_lab_runtime"
    private const val KEY_OUTPUT_MAY_BE_OWNED = "output_may_be_owned"

    fun outputMayBeOwned(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_OUTPUT_MAY_BE_OWNED, false)

    @Synchronized
    @SuppressLint("ApplySharedPref") // write-ahead safety marker must reach disk before native SEND
    fun markOutputMayBeOwned(context: Context, owned: Boolean): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_OUTPUT_MAY_BE_OWNED, false) == owned) return true
        return prefs.edit().putBoolean(KEY_OUTPUT_MAY_BE_OWNED, owned).commit()
    }

    internal fun clearForTest(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

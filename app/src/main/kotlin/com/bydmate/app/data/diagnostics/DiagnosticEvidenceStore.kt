package com.bydmate.app.data.diagnostics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.bydmate.app.data.vehicle.VehicleProfile

/** Small durable evidence ledger for successes that otherwise live only in singleton memory.
 * Each Android package owns its own prefs, so dev evidence can never confirm the stable build. */
object DiagnosticEvidenceStore {
    private const val TAG = "DiagnosticEvidence"
    private const val PREFS = "vehicle_diagnostic_evidence"
    // HUD frames arrive roughly every 300 ms and Waze can emit accessibility updates even more
    // often. The live timestamp stays in each producer's StateFlow; this durable ledger only needs
    // a coarse recovery point after process death. Throttling avoids continuous flash writes while
    // driving without making the in-app diagnostics stale.
    private const val MIN_PERSIST_INTERVAL_MS = 60_000L
    private var lastErrorLogElapsedMs = 0L

    enum class Evidence(val key: String) {
        WAZE_GUIDANCE("waze_guidance_at"),
        CLUSTER_PROJECTION("cluster_projection_at"),
        FACTORY_HUD_FRAME("factory_hud_frame_at"),
    }

    data class PackageIdentity(
        val versionLabel: String,
        /** Version + install/update identity used to invalidate evidence after an app replacement. */
        val evidenceScope: String,
    )

    private fun deviceKey(suffix: String, scope: String? = null): String = buildString {
        append(VehicleProfile.CURRENT.id)
        append('|')
        append(Build.FINGERPRINT)
        append('|')
        append(suffix)
        scope?.let {
            append('|')
            append(it)
        }
    }

    @Synchronized
    fun record(
        context: Context,
        evidence: Evidence,
        atMs: Long = System.currentTimeMillis(),
        scope: String? = null,
    ) {
        if (atMs <= 0L) return
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val key = deviceKey(evidence.key, scope)
            val previous = prefs.getLong(key, 0L)
            if (previous > 0L && atMs >= previous &&
                atMs - previous < MIN_PERSIST_INTERVAL_MS
            ) {
                return
            }
            prefs.edit().putLong(key, atMs).apply()
        } catch (error: RuntimeException) {
            // Diagnostics must never break the HUD/a11y producer that is supplying the evidence.
            // Rate-limit the warning too: a damaged prefs store plus a 300 ms HUD loop must not
            // flood logcat.
            logPersistenceError("cannot persist ${evidence.name}", error)
        }
    }

    fun timestamp(context: Context, evidence: Evidence, scope: String? = null): Long? = runCatching {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(deviceKey(evidence.key, scope), 0L)
            .takeIf { it > 0L }
    }.getOrNull()

    /** One shared package identity implementation for the producer and collector. lastUpdateTime
     * also invalidates evidence after reinstalling the same Waze version. */
    fun packageIdentity(context: Context, packageName: String): PackageIdentity? = runCatching {
        @Suppress("DEPRECATION")
        val info = context.applicationContext.packageManager.getPackageInfo(packageName, 0)
        PackageIdentity(
            versionLabel = info.versionName ?: "?",
            evidenceScope = "$packageName@${info.longVersionCode}:${info.lastUpdateTime}",
        )
    }.getOrNull()

    /** Physical effects (HUD visibility, a window actually moving, a command actuating hardware)
     * cannot be proven by a successful Binder return code. The owner can confirm them after an
     * on-car check; firmware fingerprint in the key automatically invalidates that evidence after
     * an OTA. */
    fun setUserConfirmed(context: Context, capability: CapabilityId, confirmed: Boolean) {
        try {
            val key = deviceKey("user_confirmed_${capability.name.lowercase()}")
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                if (confirmed) putLong(key, System.currentTimeMillis()) else remove(key)
            }.apply()
        } catch (error: RuntimeException) {
            // A failed evidence write must not crash the Compose click handler.
            logPersistenceError("cannot update ${capability.name} confirmation", error)
        }
    }

    fun userConfirmationAt(context: Context, capability: CapabilityId): Long? = runCatching {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(deviceKey("user_confirmed_${capability.name.lowercase()}"), 0L)
            .takeIf { it > 0L }
    }.getOrNull()

    @Synchronized
    private fun logPersistenceError(operation: String, error: RuntimeException) {
        val elapsed = SystemClock.elapsedRealtime()
        if (lastErrorLogElapsedMs == 0L ||
            elapsed - lastErrorLogElapsedMs >= MIN_PERSIST_INTERVAL_MS
        ) {
            lastErrorLogElapsedMs = elapsed
            Log.w(TAG, "$operation: ${error.message}")
        }
    }
}

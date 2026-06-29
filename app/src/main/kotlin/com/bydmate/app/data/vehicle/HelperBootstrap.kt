package com.bydmate.app.data.vehicle

import android.content.Context
import android.os.Build
import android.util.Log
import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of the helper daemon's lifecycle. Idempotent — ensureRunning()
 * converges to "one alive helper of THIS app version".
 *
 * Version awareness (the stale-daemon fix): the daemon is a detached app_process
 * under shell uid that survives app reinstalls and reboots-less updates. A daemon
 * spawned by a previous app version still answers the binder ping but lacks any
 * handler added in the update — so newer ops (sentry mode, assistant disable)
 * silently failed until a head-unit reboot. We therefore remember the app
 * versionCode the live daemon was spawned for: a mismatch means "stale", and we
 * kill the old daemon and spawn a fresh one carrying the current handlers.
 */
@Singleton
class HelperBootstrap @Inject constructor(
    private val adb: AdbOnDeviceClient,
    private val helper: HelperClient,
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /**
     * Returns true if a helper daemon matching the current app version is reachable
     * after this call.
     *
     * Order of operations:
     *   1. If a daemon spawned by THIS app version is already alive, reuse it.
     *   2. Otherwise, if the live daemon is from an older app version, kill it so the
     *      fresh spawn can take the binder service + file lock. (On a clean boot —
     *      same version, daemon simply absent — there is nothing to kill.)
     *   3. Spawn via app_process and poll the binder ping up to [POLL_ATTEMPTS] times.
     *   4. On success, persist the spawned versionCode; on exhausted poll, log and fail.
     */
    suspend fun ensureRunning(): Boolean {
        val want = installedVersionCode()
        val spawnedFor = prefs.getLong(KEY_SPAWNED_VERSION, NO_STORED_VERSION)
        // Reuse only a daemon this exact app version spawned.
        if (spawnedFor == want && helper.isAlive()) return true
        // Version changed → an older daemon may be holding the service + lock; kill it first.
        // Same version but dead (post-reboot) → nothing to kill, just respawn.
        if (spawnedFor != want) {
            adb.killHelper()
            // kill -9 is async, and the old daemon holds the exclusive lock + binder name until
            // it actually dies. The fresh daemon uses a NON-blocking tryLock() and exits cleanly
            // if the lock is still held — which would leave the STALE daemon registered, so
            // helper.isAlive() below would falsely confirm the new version. Wait (bounded) for the
            // old process to disappear before spawning. First run / no old process → heartbeat is
            // already false → the loop never runs.
            var stillAlive = adb.helperHeartbeat()
            var attempts = 0
            while (stillAlive && attempts < KILL_CONFIRM_ATTEMPTS) {
                delay(KILL_CONFIRM_INTERVAL_MS)
                attempts++
                stillAlive = adb.helperHeartbeat()
            }
            // If the stale daemon refuses to die, do NOT spawn over it: the fresh daemon would
            // lose the lock race and exit, and helper.isAlive() would then confirm the OLD daemon
            // and wrongly persist the new version. Bail so the next ensureRunning() retries the
            // kill instead of silently recording a stale daemon as current.
            if (stillAlive) {
                Log.w(TAG, "stale helper still alive after kill; not spawning to avoid lock race")
                return false
            }
        }
        if (!adb.spawnHelper()) { Log.w(TAG, "spawnHelper dispatch failed") }
        repeat(POLL_ATTEMPTS) {
            delay(POLL_INTERVAL_MS)
            if (helper.isAlive()) {
                prefs.edit().putLong(KEY_SPAWNED_VERSION, want).apply()
                return true
            }
        }
        val log = adb.readHelperLog()
        Log.w(TAG, "helper unreachable after spawn; daemon log:\n$log")
        return false
    }

    /** Cheap reachability check — no side effects. */
    suspend fun isHealthy(): Boolean = helper.isAlive()

    /** Installed versionCode of our own package; a distinct sentinel if it cannot be read
     *  (never expected — our own package always exists). The sentinel differs from the stored
     *  default so a read failure can never be mistaken for "same version, reuse". */
    private fun installedVersionCode(): Long = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrDefault(VERSION_READ_FAILED)

    companion object {
        private const val TAG = "HelperBootstrap"
        private const val PREFS = "helper"
        private const val KEY_SPAWNED_VERSION = "spawned_version_code"
        // Default for "no daemon ever spawned" — distinct from VERSION_READ_FAILED so the two
        // unknown states never collide into a false version match.
        private const val NO_STORED_VERSION = -1L
        private const val VERSION_READ_FAILED = Long.MIN_VALUE
        private const val POLL_ATTEMPTS = 15
        private const val POLL_INTERVAL_MS = 200L
        // Bounded wait for the killed daemon to actually exit before respawning.
        private const val KILL_CONFIRM_ATTEMPTS = 10
        private const val KILL_CONFIRM_INTERVAL_MS = 100L
    }
}

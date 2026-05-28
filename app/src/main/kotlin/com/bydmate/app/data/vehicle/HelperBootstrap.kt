package com.bydmate.app.data.vehicle

import android.content.Context
import android.util.Log
import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of the helper daemon's lifecycle. Idempotent — calling
 * ensureRunning() multiple times converges to "one alive helper" without
 * race conditions because the daemon's 127.0.0.1:8765 bind is exclusive
 * (second spawn fails to bind and exits cleanly).
 *
 * Pattern: connect-first via HelperClient.isAlive() ping; only spawn via
 * AdbOnDeviceClient.bootstrapHelper() when the ping fails.
 */
@Singleton
class HelperBootstrap @Inject constructor(
    @ApplicationContext private val context: Context,
    private val adb: AdbOnDeviceClient,
    private val helper: HelperClient,
) {
    /**
     * Returns true if the helper is reachable on 127.0.0.1:8765 after this call.
     *
     * Order of operations:
     *   1. Ping the daemon. If it responds (status == 0), return true immediately.
     *   2. Push helper.dex (SHA-256 verified against assets/helper.dex.sha256) + spawn via app_process.
     *   3. The bootstrap call internally polls ping for up to 3 s.
     */
    suspend fun ensureRunning(): Boolean {
        if (helper.isAlive()) return true
        val expectedSha = expectedDexSha256()
            ?: run { Log.e(TAG, "helper.dex.sha256 missing from assets — refusing to bootstrap"); return false }
        val ok = adb.bootstrapHelper(context, expectedSha)
        if (!ok) Log.w(TAG, "bootstrap returned false — helper unreachable")
        return ok
    }

    /** Cheap reachability check — no side effects. */
    suspend fun isHealthy(): Boolean = helper.isAlive()

    private fun expectedDexSha256(): String? = runCatching {
        context.assets.open(SHA_ASSET).bufferedReader().use { it.readText().trim() }
    }.getOrNull()?.takeIf { it.length == 64 }

    companion object {
        private const val TAG = "HelperBootstrap"
        private const val SHA_ASSET = "helper.dex.sha256"
    }
}

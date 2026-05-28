package com.bydmate.app.data.autoservice

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connects to the on-device ADB daemon at 127.0.0.1:5555 (DiLink has WiFi
 * ADB enabled in dev settings) using a persistent RSA keypair stored in
 * `filesDir/adb_keys/`. Once paired, exposes `exec(cmd)` for one-shot
 * shell commands.
 *
 * Why on-device ADB? `service call autoservice ...` requires either system
 * UID, hidden API access, or shell UID. BYDMate runs as a normal app —
 * ADB shell uid is the only path. See `reference_adb_on_device_pattern.md`.
 *
 * Implementation: hand-rolled binary ADB protocol in [AdbProtocolClient]
 * (no external deps, no `adblib`). Auth flow uses standard RSA pubkey on
 * port 5555 — no TLS pairing / 6-digit code, the user accepts the «Allow USB
 * debugging?» dialog directly on DiLink.
 */
interface AdbOnDeviceClient {
    /** Initiates the ADB handshake. Suspends until handshake completes or fails. */
    suspend fun connect(): Result<Unit>
    suspend fun isConnected(): Boolean
    /** Executes a one-shot shell command and returns stdout, or null on failure. */
    suspend fun exec(cmd: String): String?
    /**
     * Grants PACKAGE_USAGE_STATS appop to our own package via shell uid. The
     * only non-autoservice write we permit through this client — needed so
     * UsageStatsManager.queryEvents returns data for camera-overlay detection.
     * No-op effect if already granted. Returns true on success.
     */
    suspend fun grantUsageStatsAppop(packageName: String): Boolean

    /**
     * Pushes the bundled helper.dex (SHA-256 verified against the supplied
     * expected hash) to /data/local/tmp/helper.dex on DiLink, then spawns
     * the helper daemon via app_process. Returns true if the daemon binds
     * 127.0.0.1:8765 and responds to ping within 3 s.
     *
     * Hardcoded path + cmdline + process name — caller cannot inject. The
     * write barrier on exec() stays unchanged; this method uses a private
     * exec path on the underlying protocol object.
     */
    suspend fun bootstrapHelper(context: android.content.Context, expectedSha256: String): Boolean

    /** Read-only check: is a process named `bydmate_helper` running? */
    suspend fun helperHeartbeat(): Boolean

    /** Closes any underlying socket. Idempotent. */
    suspend fun shutdown()
}

@Singleton
class AdbOnDeviceClientImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val keyStore: AdbKeyStore,
) : AdbOnDeviceClient {

    /**
     * Test seam — UNIT TESTS ONLY. Lets the test layer swap the real
     * socket-backed protocol for a fake. Default factory creates the real
     * [AdbProtocolClient] with the persisted keypair.
     */
    @Suppress("unused")  // assigned via internal setter from tests
    internal var protocolFactory: () -> AdbProtocol = {
        AdbProtocolClient(keyStore.loadOrGenerate())
    }

    @Volatile private var protocol: AdbProtocol? = null

    @Suppress("unused")  // kept for future-proofing; AdbKeyStore already has Context.
    private val ctx = context

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val p = protocol ?: protocolFactory().also { protocol = it }
            val ok = p.connect()
            if (ok) Result.success(Unit) else Result.failure(IOException("ADB connect refused"))
        } catch (e: Exception) {
            Log.w(TAG, "connect failed: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun isConnected(): Boolean = withContext(Dispatchers.IO) {
        protocol?.isConnected() ?: false
    }

    override suspend fun exec(cmd: String): String? = withContext(Dispatchers.IO) {
        // Structural barrier against accidental WRITE — only allow GETs to autoservice.
        require(cmd.matches(WRITE_BARRIER_REGEX)) {
            "AdbOnDeviceClient: refused command (write barrier): $cmd"
        }
        val p = protocol ?: return@withContext null
        try {
            // runInterruptible bridges coroutine cancellation to the blocking
            // ADB socket read: a parent withTimeout/withTimeoutOrNull will
            // post a Thread.interrupt(), which the socket implementation
            // raises as InterruptedIOException and unwinds out of p.exec.
            // Without this wrapper, withTimeoutOrNull(900ms) abandons the
            // coroutine but the socket read keeps blocking up to 5 s
            // (SOCKET_TIMEOUT_MS), pinning single-flight in TrackingService.
            kotlinx.coroutines.runInterruptible { p.exec(cmd) }
        } catch (e: Exception) {
            Log.w(TAG, "exec failed: ${e.message}")
            null
        }
    }

    override suspend fun grantUsageStatsAppop(packageName: String): Boolean = withContext(Dispatchers.IO) {
        // Only permit our own package — never grant appops to anything else.
        require(packageName.matches(PACKAGE_NAME_REGEX)) {
            "grantUsageStatsAppop: refused package $packageName"
        }
        val cmd = "appops set $packageName GET_USAGE_STATS allow"
        val p = protocol ?: return@withContext false
        try {
            // appops prints nothing on success; null means transport error.
            // Treat any non-null output as failure (e.g. "Bad permission") too.
            val out = p.exec(cmd) ?: return@withContext false
            out.isBlank()
        } catch (e: Exception) {
            Log.w(TAG, "grantUsageStatsAppop failed: ${e.message}")
            false
        }
    }

    override suspend fun bootstrapHelper(
        context: android.content.Context,
        expectedSha256: String
    ): Boolean = withContext(Dispatchers.IO) {
        val p = protocol ?: run {
            val r = connect()
            if (r.isFailure) return@withContext false
            protocol ?: return@withContext false
        }
        try {
            val dexBytes = context.assets.open("helper.dex").use { it.readBytes() }
            val sha = java.security.MessageDigest.getInstance("SHA-256")
                .digest(dexBytes).joinToString("") { "%02x".format(it) }
            if (sha != expectedSha256) {
                Log.e(TAG, "helper.dex sha mismatch: expected=$expectedSha256 actual=$sha")
                return@withContext false
            }
            val b64 = android.util.Base64.encodeToString(dexBytes, android.util.Base64.NO_WRAP)
            val pushCmd = "echo $b64 | base64 -d > $HELPER_REMOTE_PATH && chmod 755 $HELPER_REMOTE_PATH"
            p.exec(pushCmd)
            val spawnCmd =
                "nohup sh -c 'CLASSPATH=$HELPER_REMOTE_PATH app_process /system/bin " +
                "--nice-name=$HELPER_PROCESS_NAME com.bydmate.app.helper.HelperDaemon' " +
                ">/dev/null 2>&1 &"
            p.exec(spawnCmd)
            // Poll for the daemon's ping response (max 3 s).
            repeat(15) {
                kotlinx.coroutines.delay(200)
                val pingOk = runCatching {
                    java.net.Socket().use { sock ->
                        sock.connect(java.net.InetSocketAddress("127.0.0.1", 8765), 200)
                        sock.getOutputStream().apply {
                            write(("""{"op":"ping"}""" + "\n").toByteArray())
                            flush()
                        }
                        sock.getInputStream().bufferedReader().readLine() != null
                    }
                }.getOrDefault(false)
                if (pingOk) return@withContext true
            }
            Log.w(TAG, "helper bootstrap: spawned but ping never responded")
            false
        } catch (e: Exception) {
            Log.w(TAG, "bootstrapHelper failed: ${e.message}")
            false
        }
    }

    override suspend fun helperHeartbeat(): Boolean = withContext(Dispatchers.IO) {
        val p = protocol ?: return@withContext false
        val out = runCatching { p.exec("ps -A -o NAME") }.getOrNull() ?: return@withContext false
        out.lineSequence().any { it.trim() == HELPER_PROCESS_NAME }
    }

    override suspend fun shutdown() {
        withContext(Dispatchers.IO) {
            try {
                protocol?.disconnect()
            } catch (_: Exception) { /* idempotent */ }
            protocol = null
        }
    }

    companion object {
        private const val TAG = "AdbOnDevice"

        // Block ANY write attempt at the boundary.
        // Allow only: service call autoservice <5|7|9> i32 <dev> i32 <fid>
        // Rejects tx=6 (setInt), tx=8 (setBuffer), and arbitrary shell.
        private val WRITE_BARRIER_REGEX = Regex("""^service call autoservice [579] i32 \d+ i32 -?\d+$""")

        // Narrow whitelist for grantUsageStatsAppop — only our own package.
        private val PACKAGE_NAME_REGEX = Regex("""^com\.bydmate\.app$""")

        // Helper daemon — hardcoded so neither caller can inject paths/cmdlines.
        private const val HELPER_REMOTE_PATH = "/data/local/tmp/helper.dex"
        private const val HELPER_PROCESS_NAME = "bydmate_helper"
    }
}

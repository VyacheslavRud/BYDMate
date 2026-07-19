package com.bydmate.app.service

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.bydmate.app.BuildConfig
import com.bydmate.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateChecker @Inject constructor(
    private val httpClient: OkHttpClient
) {
    internal data class ReleaseAsset(
        val name: String,
        val downloadUrl: String,
    )

    internal data class ApkIdentity(
        val packageName: String,
        val signerDigests: Set<String>,
    )

    companion object {
        private const val GITHUB_API = "https://api.github.com/repos/AndyShaman/BYDMate/releases/latest"
        private const val PREFS_NAME = "update_prefs"
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_AUTO_CHECK = "auto_check_enabled"
        private const val KEY_LAST_SEEN_VERSION = "last_seen_version"
        private const val CHECK_INTERVAL_MS = 10 * 60 * 1000L // 10 minutes (protects only against repeated launches within one session)

        internal fun selectExpectedApkAsset(
            version: String,
            assets: List<ReleaseAsset>,
        ): ReleaseAsset? {
            val expectedName = "BYDMate-v$version.apk"
            val matches = assets.filter { it.name == expectedName }
            if (matches.size != 1) return null
            return matches.single().takeIf { isHttpsUrl(it.downloadUrl) }
        }

        internal fun isTrustedUpdateIdentity(
            installed: ApkIdentity?,
            archive: ApkIdentity?,
        ): Boolean =
            installed != null &&
                archive != null &&
                archive.packageName == installed.packageName &&
                archive.signerDigests.isNotEmpty() &&
                archive.signerDigests == installed.signerDigests

        internal fun isHttpsUrl(raw: String): Boolean {
            val uri = try {
                URI(raw.trim())
            } catch (_: Exception) {
                return false
            }
            return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
        }

        fun isAutoCheckEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_CHECK, true)

        fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
        }

        fun getLastSeenVersion(context: Context): String? =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_SEEN_VERSION, null)

        fun setLastSeenVersion(context: Context, version: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_SEEN_VERSION, version).apply()
        }
    }

    data class UpdateInfo(
        val version: String,
        val downloadUrl: String,
        val releaseNotes: String
    )

    suspend fun checkForUpdate(context: Context, forceCheck: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        // GitHub releases contain the stable package. A Dev installation must never offer
        // that APK as an in-place update because it is a separate application.
        if (BuildConfig.DEBUG) return@withContext null

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0)
        val now = System.currentTimeMillis()

        if (!forceCheck && now - lastCheck < CHECK_INTERVAL_MS) return@withContext null

        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()

        val request = Request.Builder()
            .url(GITHUB_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "BYDMate-UpdateCheck")
            .build()
        val body = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("GitHub API: HTTP ${response.code}")
            }
            response.body?.string() ?: throw Exception("Пустой ответ от GitHub")
        }

        val json = JSONObject(body)
        val tagName = json.optString("tag_name", "").removePrefix("v")
        val currentVersion = getAppVersion(context)

        if (tagName.isEmpty()) throw Exception("Нет tag_name в ответе GitHub")
        if (tagName == currentVersion || !isNewer(tagName, currentVersion)) {
            return@withContext null // genuinely up to date
        }

        val assetsJson = json.optJSONArray("assets")
            ?: throw Exception("Нет assets в релизе $tagName")
        val assets = buildList {
            for (i in 0 until assetsJson.length()) {
                val asset = assetsJson.optJSONObject(i) ?: continue
                add(
                    ReleaseAsset(
                        name = asset.optString("name", ""),
                        downloadUrl = asset.optString("browser_download_url", ""),
                    )
                )
            }
        }
        val apkAsset = selectExpectedApkAsset(tagName, assets)
            ?: throw Exception(
                "В релизе $tagName должен быть ровно один HTTPS asset BYDMate-v$tagName.apk"
            )

        UpdateInfo(
            version = tagName,
            downloadUrl = apkAsset.downloadUrl,
            releaseNotes = json.optString("body", "")
        )
    }

    /**
     * Download APK and report progress via [onProgress] callback.
     * Calls [onProgress] with status strings like "Скачивание: 45%".
     * When done, triggers the install dialog.
     */
    suspend fun downloadAndInstall(
        context: Context,
        update: UpdateInfo,
        onProgress: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        if (!isHttpsUrl(update.downloadUrl)) {
            throw IllegalArgumentException("Ссылка на обновление должна использовать HTTPS")
        }
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        // Delete old file if exists
        val destFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "BYDMate-${update.version}.apk"
        )
        if (destFile.exists()) destFile.delete()

        val request = DownloadManager.Request(Uri.parse(update.downloadUrl))
            .setTitle("BYDMate ${update.version}")
            .setDescription(context.getString(R.string.update_download_notification_description))
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "BYDMate-${update.version}.apk"
            )
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)

        val downloadId = downloadManager.enqueue(request)
        var installIntentLaunched = false
        try {
            onProgress(context.getString(R.string.update_downloading_start))

            // Poll download progress
            var finished = false
            while (!finished) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                // .use{} closes the cursor on every path — the old code only closed it inside the
                // moveToFirst() block, leaking one cursor per poll when the row was momentarily absent.
                val rowPresent = downloadManager.query(query)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use false
                    val status = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    )
                    when (status) {
                        DownloadManager.STATUS_RUNNING -> {
                            val total = cursor.getLong(
                                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                            )
                            val downloaded = cursor.getLong(
                                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                            )
                            if (total > 0) {
                                val pct = (downloaded * 100 / total).toInt()
                                onProgress(context.getString(R.string.update_downloading_progress, pct))
                            }
                        }
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            finished = true
                            onProgress(context.getString(R.string.update_downloading_done))
                        }
                        DownloadManager.STATUS_FAILED -> {
                            finished = true
                            val reason = cursor.getInt(
                                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                            )
                            throw Exception(context.getString(R.string.update_download_error, reason))
                        }
                        DownloadManager.STATUS_PAUSED -> {
                            onProgress(context.getString(R.string.update_downloading_paused))
                        }
                    }
                    true
                } ?: false
                // Download row vanished (query null or cleared by the user): stop instead of spinning
                // forever every 500 ms with no row that could ever flip `finished`.
                if (!rowPresent) {
                    throw Exception(context.getString(R.string.update_download_error, -1))
                }
                if (!finished) {
                    kotlinx.coroutines.delay(500)
                }
            }

            val installedIdentity = installedIdentity(context)
            val archiveIdentity = archiveIdentity(context, destFile)
            if (!isTrustedUpdateIdentity(installedIdentity, archiveIdentity)) {
                throw IllegalStateException(
                    "APK обновления имеет другой package name или подпись и не будет установлен"
                )
            }

            // Trigger install only after package name and the complete signer set are verified.
            withContext(Dispatchers.Main) {
                installApk(context, destFile)
                installIntentLaunched = true
            }
        } catch (e: CancellationException) {
            if (!installIntentLaunched) cleanupDownload(downloadManager, downloadId, destFile)
            throw e
        } catch (e: Exception) {
            if (!installIntentLaunched) cleanupDownload(downloadManager, downloadId, destFile)
            throw e
        }
    }

    private fun cleanupDownload(downloadManager: DownloadManager, downloadId: Long, file: File) {
        runCatching { downloadManager.remove(downloadId) }
        file.delete()
    }

    private fun installApk(context: Context, file: File) {
        if (!file.exists()) throw IllegalStateException("Скачанный APK не найден")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    private fun installedIdentity(context: Context): ApkIdentity? = try {
        packageIdentity(
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        )
    } catch (_: Exception) {
        null
    }

    private fun archiveIdentity(context: Context, file: File): ApkIdentity? = try {
        context.packageManager.getPackageArchiveInfo(
            file.path,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )?.let(::packageIdentity)
    } catch (_: Exception) {
        null
    }

    private fun packageIdentity(packageInfo: PackageInfo): ApkIdentity? {
        val signers = packageInfo.signingInfo?.apkContentsSigners ?: return null
        return ApkIdentity(
            packageName = packageInfo.packageName,
            signerDigests = signers.mapTo(mutableSetOf()) { signature ->
                sha256(signature.toByteArray())
            },
        )
    }

    private fun sha256(bytes: ByteArray): String {
        val hex = "0123456789abcdef"
        return buildString(64) {
            for (byte in MessageDigest.getInstance("SHA-256").digest(bytes)) {
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
    }

    private fun getAppVersion(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        } catch (e: Exception) {
            "0.0.0"
        }
    }

    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").mapNotNull { it.toIntOrNull() }
        val l = local.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        return false
    }
}

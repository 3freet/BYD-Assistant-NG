package com.bydassistantng.ota

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.bydassistantng.R
import com.bydassistantng.BuildConfig
import com.bydassistantng.data.PreferencesRepository
import com.bydassistantng.util.AppLanguage
import com.bydassistantng.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OtaUpdater"
private val UPDATE_REPO = BuildConfig.UPDATE_REPO
private val REPO_PATTERN = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
private const val CHECK_INTERVAL_MS = 3 * 60 * 60 * 1000L // 3 hours
private const val NOTIFICATION_CHANNEL_ID = "ota_updates"
private const val NOTIFICATION_ID = 2001

const val EXTRA_OTA_VERSION = "EXTRA_OTA_VERSION"
const val EXTRA_OTA_URL = "EXTRA_OTA_URL"
const val EXTRA_OTA_NAME = "EXTRA_OTA_NAME"
const val EXTRA_OTA_BODY = "EXTRA_OTA_BODY"

/**
 * GitHub-releases-based OTA update checker/installer, built on plain OkHttp + kotlinx.serialization
 * (matching the rest of this app). Does nothing unless the build names a repository to check
 * (`BuildConfig.UPDATE_REPO`, see app/build.gradle.kts). Runs on an application-scoped
 * [CoroutineScope] rather than a caller's own, so a download survives the screen that started it
 * being closed.
 */
@Singleton
class OtaUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesRepository: PreferencesRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "BYD-Assistant-NG-Updater")
                .build()
            chain.proceed(request)
        }
        .build()

    fun checkUpdateInBackground(force: Boolean = false) {
        scope.launch {
            try {
                val releaseInfo = checkForUpdate(force)
                if (releaseInfo != null) {
                    Log.i(TAG, "New OTA version available: ${releaseInfo.tagName}")
                    preferencesRepository.setLatestOtaVersion(releaseInfo.tagName)
                    showUpdateNotification(releaseInfo)
                } else {
                    Log.d(TAG, "Assistant is up-to-date.")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error checking OTA update in background", e)
            }
        }
    }

    /** Whether this build was told which GitHub repository publishes its updates. */
    val isConfigured: Boolean get() = UPDATE_REPO.matches(REPO_PATTERN)

    suspend fun checkForUpdate(force: Boolean = false): ReleaseInfo? = withContext(Dispatchers.IO) {
        if (!isConfigured || !shouldCheck(force)) return@withContext null

        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$UPDATE_REPO/releases/latest")
                .get()
                .build()

            val release = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "GitHub releases check failed with HTTP ${response.code}")
                    return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                json.decodeFromString(GitHubRelease.serializer(), body)
            }
            preferencesRepository.setLastOtaCheckTime(System.currentTimeMillis())

            val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: run {
                    Log.w(TAG, "No APK asset found in release ${release.tagName}")
                    return@withContext null
                }

            val currentVersionName = getCurrentVersionName()
            val cleanRemoteVersion = release.tagName.removePrefix("v").removePrefix("V")

            if (isNewerVersion(cleanRemoteVersion, currentVersionName)) {
                ReleaseInfo(
                    tagName = release.tagName,
                    versionName = cleanRemoteVersion,
                    title = release.name ?: release.tagName,
                    body = release.body ?: "",
                    htmlUrl = release.htmlUrl,
                    downloadUrl = apkAsset.browserDownloadUrl,
                    apkName = apkAsset.name,
                    size = apkAsset.size,
                )
            } else {
                null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to check for OTA update", e)
            null
        }
    }

    private suspend fun shouldCheck(force: Boolean): Boolean {
        if (force) return true
        val lastCheck = preferencesRepository.getLastOtaCheckTime()
        return (System.currentTimeMillis() - lastCheck) >= CHECK_INTERVAL_MS
    }

    fun getCurrentVersionName(): String = try {
        val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        pInfo.versionName ?: "1.0.0"
    } catch (e: Exception) {
        "1.0.0"
    }

    /** Compares semantic versions (e.g. "1.0.3" > "1.0.2"); a pre-release suffix (`-beta.N`) sorts
     * below the same base version without one. */
    fun isNewerVersion(remote: String, current: String): Boolean {
        val cleanRemote = remote.trim().removePrefix("v").removePrefix("V")
        val cleanCurrent = current.trim().removePrefix("v").removePrefix("V")

        val remoteParts = cleanRemote.split("-")[0].split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = cleanCurrent.split("-")[0].split(".").mapNotNull { it.toIntOrNull() }

        for (i in 0 until maxOf(remoteParts.size, currentParts.size)) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r != c) return r > c
        }

        if (cleanRemote.contains("-") && !cleanCurrent.contains("-")) return false
        if (!cleanRemote.contains("-") && cleanCurrent.contains("-")) return true
        return false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, AppLanguage.string(context, R.string.ota_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = AppLanguage.string(context, R.string.ota_channel_desc)
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun showUpdateNotification(releaseInfo: ReleaseInfo) {
        createNotificationChannel()

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(EXTRA_OTA_VERSION, releaseInfo.tagName)
            putExtra(EXTRA_OTA_URL, releaseInfo.downloadUrl)
            putExtra(EXTRA_OTA_NAME, releaseInfo.apkName)
            putExtra(EXTRA_OTA_BODY, releaseInfo.body)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_assistant)
            .setContentTitle(AppLanguage.string(context, R.string.ota_notif_title, releaseInfo.tagName))
            .setContentText(AppLanguage.string(context, R.string.ota_notif_text, releaseInfo.tagName))
            .setStyle(NotificationCompat.BigTextStyle().bigText(AppLanguage.string(context, R.string.ota_notif_big, releaseInfo.tagName, releaseInfo.body.take(200))))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.notify(NOTIFICATION_ID, notification)
    }

    /** Downloads on the application-scoped [scope], so it survives the caller's screen closing. */
    fun downloadAndInstall(
        downloadUrl: String,
        fileName: String,
        onProgress: (progress: Float) -> Unit = {},
        onComplete: (success: Boolean) -> Unit = {},
    ) {
        scope.launch {
            val file = downloadApk(downloadUrl, fileName, onProgress)
            val success = file != null && file.exists()
            if (success) installApk(file!!) else Log.e(TAG, "OTA download failed or produced no file")
            withContext(Dispatchers.Main) { onComplete(success) }
        }
    }

    suspend fun downloadApk(downloadUrl: String, fileName: String, onProgress: (progress: Float) -> Unit = {}): File? =
        withContext(Dispatchers.IO) {
            val downloadDir = File(context.cacheDir, "ota_updates").apply { mkdirs() }
            val targetFile = File(downloadDir, fileName)
            if (targetFile.exists()) targetFile.delete()

            val request = Request.Builder().url(downloadUrl).header("User-Agent", "BYD-Assistant-NG-Updater").get().build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Download failed with HTTP ${response.code}")
                        return@withContext null
                    }
                    val body = response.body ?: return@withContext null
                    val totalBytes = body.contentLength()
                    var downloadedBytes = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(targetFile).use { output ->
                            val buffer = ByteArray(8 * 1024)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                if (totalBytes > 0) onProgress(downloadedBytes.toFloat() / totalBytes.toFloat())
                            }
                            output.flush()
                        }
                    }
                    onProgress(1.0f)
                    targetFile
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error downloading APK", e)
                targetFile.delete()
                null
            }
        }

    fun installApk(apkFile: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start APK installation", e)
        }
    }
}

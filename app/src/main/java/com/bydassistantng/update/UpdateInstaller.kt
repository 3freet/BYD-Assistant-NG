package com.bydassistantng.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.StatFs
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.bydassistantng.ui.MainActivity
import com.bydassistantng.util.AdbHelper
import com.bydassistantng.util.AppLogger
import dadb.Dadb
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UpdateInstaller"
private const val MAX_APK_BYTES = 200L * 1024 * 1024
private const val SPARE_BYTES = 64L * 1024 * 1024
private const val ADB_CONNECT_TIMEOUT_MS = 10_000L
private const val RESTART_WAIT_MS = 25_000L
private const val RELAUNCH_WATCH_SECONDS = 90
private const val APK_MIME = "application/vnd.android.package-archive"
private val SAFE_PATH = Regex("[A-Za-z0-9/._-]+")

/** What happened to an install that didn't end with this process being replaced. */
enum class InstallHandoff {
    /** The system's own installer was opened and takes it from here (the user confirms there). */
    SYSTEM_INSTALLER,
}

/**
 * Downloads an update, checks that it really is this app, and installs it.
 *
 * Installing is done the way the rest of the app already reaches the car: over the loopback ADB connection
 * the user has switched on, where `pm install` is allowed to replace the app without a confirmation screen
 * (there is no usable one on the head unit). The APK is streamed into `pm` rather than named to it, because
 * the system installer process can't open a file under this app's storage by path. Replacing the app kills
 * this process and drops the ADB connection, taking any command chained after the install down with it, so a
 * small detached watcher on the device starts the app again once the new version shows up. Without an ADB
 * connection the system installer is opened instead.
 */
@Singleton
class UpdateInstaller @Inject constructor(@ApplicationContext private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Readable by the ADB shell (which streams the file into `pm`), unlike this app's private storage. */
    private val directory: File
        get() = (context.getExternalFilesDir("update") ?: File(context.cacheDir, "update")).apply { mkdirs() }

    /** Removes anything left in the download folder by an earlier attempt. */
    fun sweep() {
        directory.listFiles()?.forEach { it.delete() }
    }

    /**
     * Downloads the release's APK, reporting progress as a fraction. Throws [UpdateFailure] when the address
     * isn't one of the repository's own release files, the file doesn't match the size or checksum GitHub
     * published for it, or the transfer fails.
     */
    suspend fun download(release: UpdateRelease, source: UpdateSource, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val url = release.apkUrl.toHttpUrlOrNull()
        if (url == null || !source.allowsDownload(url)) throw UpdateFailure(UpdateFailure.Kind.BAD_RESPONSE, "download address")
        if (release.apkSize > MAX_APK_BYTES) throw UpdateFailure(UpdateFailure.Kind.BAD_RESPONSE, "file too large")
        sweep()
        val dir = directory
        if (release.apkSize > 0 && StatFs(dir.path).availableBytes < release.apkSize + SPARE_BYTES) throw UpdateFailure(UpdateFailure.Kind.NOT_ENOUGH_SPACE)

        val part = File(dir, "update.apk.part")
        val target = File(dir, "update.apk")
        val call = client.newCall(Request.Builder().url(url).header("Accept", "application/octet-stream").build())
        val cancelOnStop = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            call.execute().use { response ->
                if (!response.isSuccessful) throw UpdateFailure(UpdateFailure.Kind.DOWNLOAD_FAILED, "HTTP ${response.code}")
                if (!source.allowsRedirectTarget(response.request.url)) throw UpdateFailure(UpdateFailure.Kind.BAD_RESPONSE, "redirected elsewhere")
                val total = response.body.contentLength().takeIf { it > 0 } ?: release.apkSize
                if (total > MAX_APK_BYTES) throw UpdateFailure(UpdateFailure.Kind.BAD_RESPONSE, "file too large")
                var reported = -1
                response.body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                val percent = (written * 100 / total).toInt().coerceAtMost(100)
                                if (percent != reported) {
                                    reported = percent
                                    onProgress(percent / 100f)
                                }
                            }
                        }
                    }
                }
            }
            if (release.apkSize > 0 && written != release.apkSize) throw UpdateFailure(UpdateFailure.Kind.CORRUPT, "size $written of ${release.apkSize}")
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256 != null && actual != release.sha256) throw UpdateFailure(UpdateFailure.Kind.CORRUPT, "checksum")
            if (!part.renameTo(target)) throw UpdateFailure(UpdateFailure.Kind.DOWNLOAD_FAILED, "rename")
            target
        } catch (e: UpdateFailure) {
            part.delete()
            throw e
        } catch (e: IOException) {
            part.delete()
            currentCoroutineContext().ensureActive() // a cancelled call surfaces as an IOException; that is a cancel, not a failure
            throw if (looksOffline(e)) UpdateFailure(UpdateFailure.Kind.OFFLINE, e.javaClass.simpleName, e)
            else UpdateFailure(UpdateFailure.Kind.DOWNLOAD_FAILED, e.javaClass.simpleName, e)
        } finally {
            cancelOnStop?.dispose()
        }
    }

    /**
     * Checks the downloaded file before it is handed to the installer: it must be this app, newer than what is
     * installed, and signed with the same key (otherwise the system would refuse it anyway, but only after
     * the app had been asked to restart).
     */
    fun verify(file: File, installedVersionCode: Long) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else signaturesFlag()
        val archive = packageArchive(file, flags) ?: throw UpdateFailure(UpdateFailure.Kind.CORRUPT, "not an installable package")
        if (archive.packageName != context.packageName) throw UpdateFailure(UpdateFailure.Kind.WRONG_PACKAGE, archive.packageName)
        if (PackageInfoCompat.getLongVersionCode(archive) <= installedVersionCode) throw UpdateFailure(UpdateFailure.Kind.NOT_NEWER)
        val installed = try {
            context.packageManager.getPackageInfo(context.packageName, flags)
        } catch (e: PackageManager.NameNotFoundException) {
            throw UpdateFailure(UpdateFailure.Kind.WRONG_SIGNATURE, "installed app unreadable", e)
        }
        val downloadedSigners = signers(archive)
        if (downloadedSigners.isEmpty() || downloadedSigners != signers(installed)) throw UpdateFailure(UpdateFailure.Kind.WRONG_SIGNATURE)
    }

    /**
     * Installs the verified [file], which carries [expectedVersionCode]. When the install goes through silently
     * this never returns, because the system replaces the app and ends this process; it returns only when the
     * system installer was opened instead, and throws [UpdateFailure] when nothing worked.
     */
    suspend fun install(file: File, expectedVersionCode: Long): InstallHandoff {
        val adb = AdbHelper.connect(context, ADB_CONNECT_TIMEOUT_MS, socketTimeoutMs = 0)
        if (adb != null) installSilently(adb, file, expectedVersionCode) // only ever leaves by throwing
        AppLogger.log(TAG, "No local ADB connection, falling back to the system installer")
        return openSystemInstaller(file)
    }

    private suspend fun installSilently(adb: Dadb, file: File, expectedVersionCode: Long): Nothing {
        val path = file.absolutePath
        if (!SAFE_PATH.matches(path)) throw UpdateFailure(UpdateFailure.Kind.INSTALL_REJECTED, "unexpected download path")
        val component = "${context.packageName}/${MainActivity::class.java.name}"
        AppLogger.log(TAG, "Installing ${file.length()} bytes over local ADB")
        val response = try {
            withContext(Dispatchers.IO) {
                adb.shell(relaunchWatcher(component, expectedVersionCode))
                adb.shell("cat $path | pm install -r -S ${file.length()}")
            }
        } catch (e: IOException) {
            // The connection dropping is what a successful install looks like from here: the system has just
            // replaced the app and killed this process. If we are still alive, wait to find out which it was.
            AppLogger.log(TAG, "ADB connection ended during the install: ${e.javaClass.simpleName}")
            null
        } finally {
            runCatching { adb.close() }
        }
        if (response != null) {
            AppLogger.log(TAG, "pm install exit ${response.exitCode}: ${response.allOutput.trim().take(200)}")
            UpdateFailure.fromInstallOutput(response.allOutput)?.let { throw it }
            if (response.exitCode != 0) throw UpdateFailure(UpdateFailure.Kind.INSTALL_REJECTED, "exit ${response.exitCode}")
        }
        delay(RESTART_WAIT_MS)
        throw UpdateFailure(UpdateFailure.Kind.INSTALL_TIMEOUT)
    }

    /**
     * A shell command that starts a detached background process on the device, which waits (up to
     * [RELAUNCH_WATCH_SECONDS]) for the package to report [versionCode] and then opens [component]. Detached so it
     * outlives the ADB connection, which closes when the system replaces this app; bounded so a failed install
     * never pops the app open later. The command itself returns at once.
     */
    private fun relaunchWatcher(component: String, versionCode: Long): String {
        val pkg = context.packageName
        val watch = "i=0; while [ \$i -lt $RELAUNCH_WATCH_SECONDS ]; do " +
            "if dumpsys package $pkg | grep -q versionCode=$versionCode; then sleep 1; am start -n $component; exit 0; fi; " +
            "sleep 1; i=\$((i+1)); done"
        return "setsid nohup sh -c '$watch' > /dev/null 2>&1 < /dev/null &"
    }

    private fun openSystemInstaller(file: File): InstallHandoff {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw UpdateFailure(UpdateFailure.Kind.NO_INSTALLER, cause = e)
        } catch (e: SecurityException) {
            throw UpdateFailure(UpdateFailure.Kind.NO_INSTALLER, e.javaClass.simpleName, e)
        }
        return InstallHandoff.SYSTEM_INSTALLER
    }

    private fun packageArchive(file: File, flags: Int): PackageInfo? {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.path, flags)
        }
        return info
    }

    @Suppress("DEPRECATION")
    private fun signaturesFlag(): Int = PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<Signature> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        } else {
            info.signatures?.toSet().orEmpty()
        }

    private fun looksOffline(e: IOException) = e is UnknownHostException || e is ConnectException || e is SocketTimeoutException
}

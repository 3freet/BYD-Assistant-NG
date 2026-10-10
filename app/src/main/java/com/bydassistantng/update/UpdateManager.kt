package com.bydassistantng.update

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import com.bydassistantng.BuildConfig
import com.bydassistantng.data.PreferencesRepository
import com.bydassistantng.gemini.LiveConversationController
import com.bydassistantng.util.AppLogger
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UpdateManager"
private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
private const val SCHEDULER_TICK_MS = 60 * 60 * 1000L

sealed interface UpdateState {
    /** Nothing has been checked yet in this run of the app. */
    data object Idle : UpdateState
    data object Checking : UpdateState

    /** [channelLatest] is the newest build the channel offers (older than, or the same as, what is installed). */
    data class UpToDate(val checkedAt: Long, val channelLatest: AppVersion?) : UpdateState
    data class Available(val release: UpdateRelease) : UpdateState
    data class Downloading(val release: UpdateRelease, val fraction: Float) : UpdateState
    data class Installing(val release: UpdateRelease) : UpdateState
    data class AwaitingSystemInstaller(val release: UpdateRelease) : UpdateState

    /** [release] is set when the failure happened while installing it, so it can be tried again. */
    data class Failed(val failure: UpdateFailure, val release: UpdateRelease?) : UpdateState

    val isInstalling: Boolean get() = this is Downloading || this is Installing || this is AwaitingSystemInstaller
}

/** The build that is running now. */
data class InstalledBuild(val versionName: String, val versionCode: Long) {
    val channel: UpdateChannel get() = UpdateChannel.ofVersionName(versionName)
}

/**
 * Looks for a newer build on the chosen channel, tells the user, and installs it on their say-so.
 *
 * Checks cost almost nothing: GitHub answers a repeat request with "not modified" (which is free and does not
 * count against its rate limit) and the last answer is kept on disk, so the Updates screen is current the
 * moment the app opens, even offline. Nothing is installed without a tap, and never during a conversation.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: PreferencesRepository,
    private val installer: UpdateInstaller,
    private val notifier: UpdateNotifier,
    private val conversation: Lazy<LiveConversationController>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkLock = Mutex()
    private var installJob: Job? = null

    @Volatile private var source: UpdateSource? = UpdateSource.forRepo(BuildConfig.UPDATE_REPO)
    @Volatile private var sourceIsTestOverride = false

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cache(Cache(File(context.cacheDir, "update_http"), 2L * 1024 * 1024))
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "BYD-Assistant-NG/${BuildConfig.VERSION_NAME}").build())
        }
        .build()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _justUpdatedTo = MutableStateFlow<String?>(null)
    /** The version name just installed, until the user dismisses the note about it. */
    val justUpdatedTo: StateFlow<String?> = _justUpdatedTo.asStateFlow()

    val installed: InstalledBuild by lazy {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        InstalledBuild(info.versionName ?: "", PackageInfoCompat.getLongVersionCode(info))
    }

    /** Whether this build can update itself: it was told where its updates live, and is not a local developer build. */
    val isSupported: Boolean get() = source != null && (!BuildConfig.DEBUG || sourceIsTestOverride)

    /** The channel to follow: the user's choice, or else the one the installed build came from. */
    val channel: Flow<UpdateChannel> = preferences.updateChannel.map { UpdateChannel.fromId(it) ?: installed.channel }
    val autoCheck: Flow<Boolean> = preferences.autoCheckUpdates

    /** Called once when the app starts: tidies up after an earlier install and keeps the checks going. */
    fun start() {
        scope.launch {
            installer.sweep()
            noteFinishedInstall()
            val current = source
            if (current != null && isSupported) {
                // Whatever was found last time, shown immediately and without a network.
                runCatching { applyCatalog(fetch(current, cacheOnly = true), notify = false) }
            }
            while (isActive) {
                if (isSupported && preferences.autoCheckUpdates.first() && System.currentTimeMillis() - preferences.lastUpdateCheck.first() >= AUTO_CHECK_INTERVAL_MS) {
                    check(userInitiated = false)
                }
                delay(SCHEDULER_TICK_MS)
            }
        }
    }

    fun checkNow() {
        scope.launch { check(userInitiated = true) }
    }

    /** Checks if nothing has been checked yet this run, for when the Updates screen opens. */
    fun checkIfNeverChecked() {
        if (_state.value == UpdateState.Idle && isSupported) checkNow()
    }

    fun setChannel(channel: UpdateChannel) {
        scope.launch {
            preferences.setUpdateChannel(channel.id)
            if (isSupported) check(userInitiated = true)
        }
    }

    fun setAutoCheck(enabled: Boolean) {
        scope.launch { preferences.setAutoCheckUpdates(enabled) }
    }

    fun dismissJustUpdated() {
        _justUpdatedTo.value = null
    }

    fun install(release: UpdateRelease) {
        val from = source ?: return
        if (installJob?.isActive == true) return
        installJob = scope.launch {
            try {
                refuseDuringConversation()
                _state.value = UpdateState.Downloading(release, 0f)
                val file = installer.download(release, from) { _state.value = UpdateState.Downloading(release, it) }
                _state.value = UpdateState.Installing(release)
                installer.verify(file, installed.versionCode)
                refuseDuringConversation() // the download takes a while; the user may have started talking meanwhile
                preferences.setPendingInstall(release.version.code.toString())
                notifier.clear()
                when (installer.install(file, release.version.code.toLong())) {
                    InstallHandoff.SYSTEM_INSTALLER -> _state.value = UpdateState.AwaitingSystemInstaller(release)
                }
            } catch (e: CancellationException) {
                installer.sweep()
                _state.value = UpdateState.Available(release)
                throw e
            } catch (e: UpdateFailure) {
                AppLogger.log(TAG, "Update to ${release.version} failed: ${e.message}")
                failInstall(release, e)
            } catch (e: Exception) {
                AppLogger.logError(TAG, "Update to ${release.version} failed unexpectedly", e)
                failInstall(release, UpdateFailure(UpdateFailure.Kind.DOWNLOAD_FAILED, e.javaClass.simpleName, e))
            }
        }
    }

    fun cancelDownload() {
        if (_state.value is UpdateState.Downloading) installJob?.cancel()
    }

    /** Points this debug build at a local release list, to exercise the whole flow without publishing anything. */
    fun useTestSource(releasesUrl: String): Boolean {
        val testSource = UpdateSource.forTesting(releasesUrl) ?: return false
        source = testSource
        sourceIsTestOverride = true
        _state.value = UpdateState.Idle
        return true
    }

    private suspend fun failInstall(release: UpdateRelease, failure: UpdateFailure) {
        installer.sweep()
        preferences.setPendingInstall("")
        _state.value = UpdateState.Failed(failure, release)
    }

    private fun refuseDuringConversation() {
        if (conversation.get().inConversation) throw UpdateFailure(UpdateFailure.Kind.BUSY)
    }

    private suspend fun check(userInitiated: Boolean) {
        val from = source
        if (from == null || !isSupported) {
            if (userInitiated) _state.value = UpdateState.Failed(UpdateFailure(UpdateFailure.Kind.UNSUPPORTED), null)
            return
        }
        if (_state.value.isInstalling) return
        checkLock.withLock {
            if (userInitiated) _state.value = UpdateState.Checking
            try {
                val releases = withContext(Dispatchers.IO) { fetch(from, cacheOnly = false) }
                preferences.setLastUpdateCheck(System.currentTimeMillis())
                applyCatalog(releases, notify = !userInitiated)
            } catch (e: UpdateFailure) {
                AppLogger.log(TAG, "Update check failed: ${e.message}")
                // A background check that couldn't reach GitHub is not worth showing an error for.
                if (userInitiated) _state.value = UpdateState.Failed(e, null)
            }
        }
    }

    private suspend fun applyCatalog(releases: List<UpdateRelease>, notify: Boolean) {
        val newest = ReleaseCatalog.latest(releases, channel.first())
        val next = if (newest != null && newest.version.code > installed.versionCode) {
            UpdateState.Available(newest)
        } else {
            UpdateState.UpToDate(System.currentTimeMillis(), newest?.version)
        }
        _state.update { current -> if (current.isInstalling) current else next }
        if (next is UpdateState.Available) {
            if (notify && preferences.notifiedUpdateTag.first() != next.release.tag) {
                notifier.show(next.release)
                preferences.setNotifiedUpdateTag(next.release.tag)
            }
        } else {
            notifier.clear()
        }
    }

    /** After an install the process is new: if the build it was waiting for is the one now running, say so. */
    private suspend fun noteFinishedInstall() {
        val pending = preferences.pendingInstall.first()
        if (pending.isEmpty()) return
        preferences.setPendingInstall("")
        if (pending == installed.versionCode.toString()) {
            AppLogger.log(TAG, "Updated to ${installed.versionName}")
            _justUpdatedTo.value = installed.versionName
            notifier.clear()
        }
    }

    private fun fetch(from: UpdateSource, cacheOnly: Boolean): List<UpdateRelease> {
        val request = Request.Builder()
            .url(from.releasesUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .apply {
                // Answer from the copy on disk, however old, and fail rather than touch the network.
                if (cacheOnly) cacheControl(CacheControl.Builder().onlyIfCached().maxStale(Int.MAX_VALUE, TimeUnit.SECONDS).build())
            }
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw UpdateFailure.forHttp(response.code, response.header("x-ratelimit-remaining"))
                return try {
                    ReleaseCatalog.parse(response.body.string())
                } catch (e: SerializationException) {
                    throw UpdateFailure(UpdateFailure.Kind.BAD_RESPONSE, cause = e)
                } catch (e: IllegalArgumentException) {
                    throw UpdateFailure(UpdateFailure.Kind.BAD_RESPONSE, cause = e)
                }
            }
        } catch (e: UpdateFailure) {
            throw e
        } catch (e: IOException) {
            throw UpdateFailure(UpdateFailure.Kind.OFFLINE, e.javaClass.simpleName, e)
        }
    }
}

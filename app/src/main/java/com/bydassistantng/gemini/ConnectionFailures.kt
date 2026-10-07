package com.bydassistantng.gemini

import android.content.Context
import androidx.annotation.StringRes
import com.bydassistantng.R
import com.bydassistantng.util.AppLanguage
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Why a connection to Gemini failed, in the terms that decide what the user is told. */
enum class FailureKind { NO_NETWORK, API_KEY_REJECTED, BUSY, SERVER_REJECTED, OTHER }

object ConnectionFailures {
    private val DROPPED = Regex("(?i)(connection (abort|reset|refused|closed)|software caused|network is unreachable|broken pipe|unable to resolve host|no address associated)")

    /** A thrown error, plus the HTTP status if the WebSocket upgrade got an answer at all. */
    fun classify(t: Throwable?, httpCode: Int? = null): FailureKind {
        when (httpCode) {
            400, 401, 403 -> return FailureKind.API_KEY_REJECTED
            429 -> return FailureKind.BUSY
            404 -> return FailureKind.SERVER_REJECTED
        }
        var cause: Throwable? = t
        var depth = 0
        while (cause != null && depth++ < 6) {
            when (cause) {
                is UnknownHostException, is ConnectException, is NoRouteToHostException, is SocketTimeoutException,
                is EOFException, is SSLException -> return FailureKind.NO_NETWORK
                is SocketException -> if (DROPPED.containsMatchIn(cause.message.orEmpty())) return FailureKind.NO_NETWORK
            }
            if (DROPPED.containsMatchIn(cause.message.orEmpty())) return FailureKind.NO_NETWORK
            cause = cause.cause
        }
        return FailureKind.OTHER
    }

    /** The server closed the session with a code and reason (anything but a normal close). */
    fun classifyClose(code: Int, reason: String): FailureKind = when {
        reason.contains("api key", ignoreCase = true) || reason.contains("permission", ignoreCase = true) -> FailureKind.API_KEY_REJECTED
        reason.contains("quota", ignoreCase = true) || reason.contains("exhausted", ignoreCase = true) -> FailureKind.BUSY
        code == 1007 || code == 1003 || code == 1008 -> FailureKind.SERVER_REJECTED
        else -> FailureKind.OTHER
    }
}

/** The text shown for a failure, as string resources (so it follows the app language). */
object UserMessages {
    @StringRes
    fun forFailure(kind: FailureKind): Int = when (kind) {
        FailureKind.NO_NETWORK -> R.string.fail_no_network
        FailureKind.API_KEY_REJECTED -> R.string.fail_api_key
        FailureKind.BUSY -> R.string.fail_busy
        FailureKind.SERVER_REJECTED, FailureKind.OTHER -> R.string.fail_generic
    }

    // Not `const`: resource ids are not compile-time constants.
    @StringRes val NO_API_KEY: Int = R.string.fail_no_key
    @StringRes val CONNECTION_LOST: Int = R.string.fail_lost

    /** [kind]'s message in the app language right now. */
    fun text(context: Context, kind: FailureKind): String = AppLanguage.string(context, forFailure(kind))
}

package com.bydassistantng.ui.settings

import android.content.Context
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.bydassistantng.R
import com.bydassistantng.update.UpdateChannel
import com.bydassistantng.update.UpdateFailure
import com.bydassistantng.util.AppLanguage
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The sentence that explains [this] failure in the app's language. */
@Composable
fun UpdateFailure.userMessage(): String = when (kind) {
    UpdateFailure.Kind.UNSUPPORTED -> stringResource(R.string.update_err_unsupported)
    UpdateFailure.Kind.BUSY -> stringResource(R.string.update_err_busy)
    UpdateFailure.Kind.OFFLINE -> stringResource(R.string.update_err_offline)
    UpdateFailure.Kind.RATE_LIMITED -> stringResource(R.string.update_err_rate_limited)
    UpdateFailure.Kind.SERVER_ERROR -> stringResource(R.string.update_err_server, detail ?: "")
    UpdateFailure.Kind.BAD_RESPONSE -> stringResource(R.string.update_err_bad_response)
    UpdateFailure.Kind.NOT_ENOUGH_SPACE -> stringResource(R.string.update_err_no_space)
    UpdateFailure.Kind.DOWNLOAD_FAILED -> stringResource(R.string.update_err_download)
    UpdateFailure.Kind.CORRUPT -> stringResource(R.string.update_err_corrupt)
    UpdateFailure.Kind.WRONG_PACKAGE -> stringResource(R.string.update_err_wrong_package)
    UpdateFailure.Kind.WRONG_SIGNATURE -> stringResource(R.string.update_err_wrong_signature)
    UpdateFailure.Kind.NOT_NEWER -> stringResource(R.string.update_err_not_newer)
    UpdateFailure.Kind.NO_INSTALLER -> stringResource(R.string.update_err_no_installer)
    UpdateFailure.Kind.INSTALL_REJECTED -> stringResource(R.string.update_err_install, detail ?: "")
    UpdateFailure.Kind.INSTALL_TIMEOUT -> stringResource(R.string.update_err_install_timeout)
}

@Composable
fun UpdateChannel.label(): String = stringResource(if (this == UpdateChannel.BETA) R.string.channel_beta else R.string.channel_stable)

/** A release's publication time (`2026-10-10T12:00:00Z`) as a date in the app language; empty when it can't be read. */
fun formatPublished(context: Context, iso: String): String {
    val parsed = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(iso)
    }.getOrNull() ?: return ""
    return DateFormat.getDateInstance(DateFormat.MEDIUM, AppLanguage.locale(context)).format(parsed)
}

fun formatCheckedAt(context: Context, millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, AppLanguage.locale(context)).format(Date(millis))

fun formatSize(context: Context, bytes: Long): String = if (bytes > 0) Formatter.formatShortFileSize(context, bytes) else ""

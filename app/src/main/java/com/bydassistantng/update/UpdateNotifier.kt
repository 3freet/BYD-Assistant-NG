package com.bydassistantng.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bydassistantng.R
import com.bydassistantng.ui.EXTRA_OPEN_UPDATES
import com.bydassistantng.ui.MainActivity
import com.bydassistantng.util.AppLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val CHANNEL_ID = "app_updates"
private const val LEGACY_CHANNEL_ID = "ota_updates"
private const val NOTIFICATION_ID = 2001

/** The "update available" notification. Quiet on purpose: a new version is no reason to light up a dashboard. */
@Singleton
class UpdateNotifier @Inject constructor(@ApplicationContext private val context: Context) {

    fun show(release: UpdateRelease) {
        val notifications = NotificationManagerCompat.from(context)
        if (!notifications.areNotificationsEnabled()) return
        createChannel()
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_OPEN_UPDATES, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_assistant)
            .setContentTitle(AppLanguage.string(context, R.string.update_notif_title, release.version.toString()))
            .setContentText(AppLanguage.string(context, R.string.update_notif_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            notifications.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notifications were switched off between the check above and now; nothing to tell anyone.
        }
    }

    fun clear() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) // from the previous updater
        val channel = NotificationChannel(CHANNEL_ID, AppLanguage.string(context, R.string.update_channel_name), NotificationManager.IMPORTANCE_LOW)
        channel.description = AppLanguage.string(context, R.string.update_channel_desc)
        manager.createNotificationChannel(channel)
    }
}

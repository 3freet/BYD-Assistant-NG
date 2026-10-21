package com.bydassistantng.service

import android.service.notification.NotificationListenerService

/**
 * Does nothing. It exists only so that the app can be a "notification listener", which is what the system asks of an app
 * that wants to see which media sessions are playing (see [com.bydassistantng.audio.MediaSources]). It never reads a
 * notification: none of the callbacks is implemented.
 */
class MediaAccessService : NotificationListenerService()

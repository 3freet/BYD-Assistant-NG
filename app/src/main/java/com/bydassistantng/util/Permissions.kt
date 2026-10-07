package com.bydassistantng.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Checked at the moment a turn starts, never assumed from onboarding: the system can hand out
 * RECORD_AUDIO as a one-time grant ("Only this time") that Android auto-revokes later — observed on
 * a head unit, where the grant had silently lapsed and every tap just failed to record.
 */
fun Context.hasMicPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

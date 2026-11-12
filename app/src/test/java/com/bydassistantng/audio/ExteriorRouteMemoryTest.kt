package com.bydassistantng.audio

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExteriorRouteMemoryTest {
    /** Preferences kept in a map; counts the commits, since what matters is that a mark is on disk when it is made. */
    private class FakePrefs : SharedPreferences {
        val values = HashMap<String, Any?>()
        var commits = 0
        var applies = 0

        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()

            override fun putString(key: String?, value: String?) = apply { pending[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { pending[key!!] = values }
            override fun putInt(key: String?, value: Int) = apply { pending[key!!] = value }
            override fun putLong(key: String?, value: Long) = apply { pending[key!!] = value }
            override fun putFloat(key: String?, value: Float) = apply { pending[key!!] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { pending[key!!] = value }
            override fun remove(key: String?) = apply { pending[key!!] = null }
            override fun clear() = apply { values.clear() }

            override fun commit(): Boolean {
                commits++
                values.putAll(pending)
                return true
            }

            override fun apply() {
                applies++
                values.putAll(pending)
            }
        }
    }

    @Test
    fun nothingIsRememberedToStartWith() {
        assertFalse(ExteriorRouteMemory(FakePrefs()).possiblyOn)
    }

    @Test
    fun aRouteAskedForIsRememberedUntilItsSwitchOffIsSeen() {
        val memory = ExteriorRouteMemory(FakePrefs())
        memory.markOn()
        assertTrue(memory.possiblyOn)
        memory.clear()
        assertFalse(memory.possiblyOn)
    }

    @Test
    fun theMemorySurvivesTheDeathOfTheAppThatWroteIt() {
        // The same preferences read by a new object: what a next run of the app has.
        val prefs = FakePrefs()
        ExteriorRouteMemory(prefs).markOn()
        assertTrue(ExteriorRouteMemory(prefs).possiblyOn)
    }

    @Test
    fun theMarkIsWrittenAtOnceNotLater() {
        // The app may be gone the next moment, so a mark made with apply() (written later, in the background) is not good enough.
        val prefs = FakePrefs()
        ExteriorRouteMemory(prefs).markOn()
        assertEquals(1, prefs.commits)
        assertEquals(0, prefs.applies)
    }

    @Test
    fun clearingWhatWasNeverMarkedWritesNothing() {
        val prefs = FakePrefs()
        ExteriorRouteMemory(prefs).clear()
        assertEquals(0, prefs.commits)
    }
}

package me.acardia.amalor.xposed

import android.content.SharedPreferences
import androidx.annotation.Keep
import java.util.concurrent.ConcurrentHashMap

/** Process-scoped hot-path flags; only preference changes read the remote preference again. */
internal class CachedBooleanPreferences(private val preferences: SharedPreferences) {
    private val values = ConcurrentHashMap<String, Boolean>()

    // SharedPreferences holds listeners weakly. Keep this reference through R8 optimization.
    @field:Keep
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null) values.clear()
        else values.computeIfPresent(key) { _, _ -> prefs.getBoolean(key, false) }
    }

    init { preferences.registerOnSharedPreferenceChangeListener(listener) }

    fun get(key: String): Boolean = values[key] ?: values.computeIfAbsent(key) { preferences.getBoolean(it, false) }
}

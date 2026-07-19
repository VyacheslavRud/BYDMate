package com.bydmate.app.demo

import android.content.Context
import com.bydmate.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide switch for the isolated Dev demo environment.
 *
 * Release builds can never enable it. Dev defaults to enabled so a fresh emulator
 * install is useful before it has access to any BYD services.
 */
object DemoMode {
    private const val PREFS_NAME = "bydmate_dev_demo"
    private const val KEY_ENABLED = "enabled"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _dataRevision = MutableStateFlow(0L)
    val dataRevision: StateFlow<Long> = _dataRevision.asStateFlow()

    fun initialize(context: Context) {
        _enabled.value = isEnabled(context)
    }

    fun isEnabled(context: Context): Boolean {
        if (!BuildConfig.DEBUG) {
            _enabled.value = false
            return false
        }
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)
        _enabled.value = value
        return value
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val effective = BuildConfig.DEBUG && enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, effective)
            .apply()
        _enabled.value = effective
    }

    internal fun notifyDataChanged() {
        _dataRevision.value += 1L
    }
}

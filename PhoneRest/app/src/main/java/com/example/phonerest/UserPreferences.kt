package com.example.phonerest

import android.content.Context

object UserPreferences {
    const val MIN_LIMIT_MINUTES = 5
    const val MAX_LIMIT_MINUTES = 60
    const val DEFAULT_LIMIT_MINUTES = 30
    const val MIN_REMINDER_MINUTES = 1
    const val MAX_REMINDER_MINUTES = 5
    const val DEFAULT_REMINDER_MINUTES = 5

    private const val FILE_NAME = "phone_rest"
    private const val KEY_LIMIT_MINUTES = "limit_minutes"
    private const val KEY_MONITORING_ENABLED = "monitoring_enabled"
    private const val KEY_REMINDER_ENABLED = "reminder_enabled"
    private const val KEY_REMINDER_MINUTES = "reminder_minutes"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun getLimitMinutes(context: Context): Int = prefs(context)
        .getInt(KEY_LIMIT_MINUTES, DEFAULT_LIMIT_MINUTES)
        .coerceIn(MIN_LIMIT_MINUTES, MAX_LIMIT_MINUTES)

    fun setLimitMinutes(context: Context, minutes: Int) {
        prefs(context).edit()
            .putInt(KEY_LIMIT_MINUTES, minutes.coerceIn(MIN_LIMIT_MINUTES, MAX_LIMIT_MINUTES))
            .apply()
    }

    fun isMonitoringEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MONITORING_ENABLED, false)

    fun setMonitoringEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MONITORING_ENABLED, enabled).apply()
    }

    fun isReminderEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REMINDER_ENABLED, true)

    fun setReminderEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_REMINDER_ENABLED, enabled).apply()
    }

    fun getReminderMinutes(context: Context): Int = prefs(context)
        .getInt(KEY_REMINDER_MINUTES, DEFAULT_REMINDER_MINUTES)
        .coerceIn(MIN_REMINDER_MINUTES, MAX_REMINDER_MINUTES)

    fun setReminderMinutes(context: Context, minutes: Int) {
        prefs(context).edit()
            .putInt(
                KEY_REMINDER_MINUTES,
                minutes.coerceIn(MIN_REMINDER_MINUTES, MAX_REMINDER_MINUTES)
            )
            .apply()
    }
}

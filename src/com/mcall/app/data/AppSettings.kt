package com.mcall.app.data

import android.content.Context

/** User preferences. Single source of truth read by both UI and the record service. */
object AppSettings {
    private const val PREF = "mcall"
    private const val KEY_AUTO = "auto_record"
    private const val KEY_SPEED = "default_speed"
    private const val KEY_ENCRYPT = "encrypt_new"

    fun autoRecord(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, true)

    fun setAutoRecord(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO, enabled).apply()
    }

    /**
     * Whether recordings are encrypted. Applies to files recorded from now on;
     * existing files keep whatever protection they were created with.
     */
    fun encryptNew(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ENCRYPT, true)

    fun setEncryptNew(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENCRYPT, enabled).apply()
    }

    fun defaultSpeed(ctx: Context): Float =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getFloat(KEY_SPEED, 1.0f)

    fun setDefaultSpeed(ctx: Context, speed: Float) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_SPEED, speed).apply()
    }
}

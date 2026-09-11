package com.mcall.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Locale-aware display formatting. Uses the device locale so Persian shows Persian digits. */
object Format {
    private val DATE_TIME = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val TIME = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val DAY = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())

    fun dateTime(d: Date): String = DATE_TIME.format(d)
    fun time(d: Date): String = TIME.format(d)
    fun day(d: Date): String = DAY.format(d)

    fun duration(ms: Int): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(Locale.getDefault(), s / 60, s % 60)
    }

    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%d KB".format(Locale.getDefault(), bytes / 1024)
        else -> "%.1f MB".format(Locale.getDefault(), bytes / (1024.0 * 1024.0))
    }
}

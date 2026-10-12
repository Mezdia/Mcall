package com.mcall.app.util

import android.text.BidiFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Locale-aware display formatting. Uses the device locale so Persian shows Persian
 * digits, and isolates every numeric run with directional marks — otherwise the bidi
 * algorithm reorders dates and "a / b" pairs inside RTL text.
 */
object Format {
    private val DATE_TIME = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val TIME = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val DAY = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val BIDI: BidiFormatter get() = BidiFormatter.getInstance()

    fun dateTime(d: Date): String = iso(DATE_TIME.format(d))
    fun time(d: Date): String = iso(TIME.format(d))
    fun day(d: Date): String = iso(DAY.format(d))

    fun duration(ms: Int): String = iso(rawDuration(ms))

    /** "current / total" as one isolated run, so the slash cannot reorder in RTL. */
    fun positionRange(currentMs: Int, totalMs: Int): String =
        iso("${rawDuration(currentMs)} / ${rawDuration(totalMs)}")

    private fun rawDuration(ms: Int): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(Locale.getDefault(), s / 60, s % 60)
    }

    fun size(bytes: Long): String = iso(
        when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%d KB".format(Locale.getDefault(), bytes / 1024)
            else -> "%.1f MB".format(Locale.getDefault(), bytes / (1024.0 * 1024.0))
        }
    )

    fun iso(text: String): String = BIDI.unicodeWrap(text)
}

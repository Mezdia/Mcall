package com.mcall.app.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One recording on disk.
 * Names: yyyyMMdd_HHmmss_DIR_number.m4a[.enc] or selftest_timestamp.m4a[.enc].
 * The .enc suffix means the file is encrypted with the password that was current
 * when it was recorded, so files keep their own era regardless of later changes.
 */
data class Recording(
    val file: File,
    val direction: String,
    val number: String,
    val date: Date,
    val sizeBytes: Long,
    val encrypted: Boolean
) {
    companion object {
        private val NAME_RE = Regex("""^(\d{8}_\d{6})_([A-Z]+)_(.+)\.m4a(\.enc)?$""")
        private val SELF_RE = Regex("""^selftest_(\d{8}_\d{6})\.m4a(\.enc)?$""")
        private val FMT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

        fun parse(f: File): Recording {
            val encrypted = f.name.endsWith(".enc")
            NAME_RE.matchEntire(f.name)?.let { m ->
                return Recording(
                    file = f,
                    direction = m.groupValues[2],
                    number = m.groupValues[3],
                    date = parseDate(m.groupValues[1], f),
                    sizeBytes = f.length(),
                    encrypted = encrypted
                )
            }
            SELF_RE.matchEntire(f.name)?.let { m ->
                return Recording(
                    file = f,
                    direction = "SELFTEST",
                    number = "",
                    date = parseDate(m.groupValues[1], f),
                    sizeBytes = f.length(),
                    encrypted = encrypted
                )
            }
            return Recording(f, "UNK", "", Date(f.lastModified()), f.length(), encrypted)
        }

        private fun parseDate(stamp: String, f: File): Date =
            runCatching { FMT.parse(stamp) }.getOrNull() ?: Date(f.lastModified())
    }
}

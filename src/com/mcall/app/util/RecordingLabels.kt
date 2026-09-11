package com.mcall.app.util

import android.content.Context
import com.mcall.app.R
import com.mcall.app.data.Recording

/** Localized labels shared by the list, the player and the media session. */
fun Recording.directionRes(): Int = when (direction) {
    "IN" -> R.string.dir_in
    "OUT" -> R.string.dir_out
    "MISSED" -> R.string.dir_missed
    "SELFTEST" -> R.string.dir_selftest
    else -> R.string.dir_unknown
}

/** The phone number when known, otherwise a localized call-type label. */
fun Recording.title(ctx: Context): String =
    number.ifEmpty { ctx.getString(directionRes()) }

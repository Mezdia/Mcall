package com.mcall.app.service

import android.content.Context
import android.content.Intent
import android.os.Build
import com.mcall.app.data.AppSettings

/**
 * The single entry point for turning recording on and off.
 *
 * Both the main screen button and the settings switch go through here, so the
 * stored preference and the live service can never disagree about the state.
 */
object RecordingController {

    fun isEnabled(ctx: Context): Boolean = AppSettings.autoRecord(ctx)

    fun set(ctx: Context, enabled: Boolean) {
        AppSettings.setAutoRecord(ctx, enabled)
        val intent = Intent(ctx, CallRecordService::class.java)
        if (enabled) {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent) else ctx.startService(intent)
        } else {
            ctx.stopService(intent)
        }
    }
}

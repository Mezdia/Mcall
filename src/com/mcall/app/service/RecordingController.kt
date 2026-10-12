package com.mcall.app.service

import android.content.Context
import android.content.Intent
import com.mcall.app.data.AppSettings

/**
 * The single entry point for arming and disarming recording.
 *
 * Arming stores a preference only — it deliberately does not start anything. The recorder
 * is brought up per call by [PhoneStateReceiver], so an armed app costs no battery and
 * shows no notification until a call is actually happening.
 */
object RecordingController {

    fun isEnabled(ctx: Context): Boolean = AppSettings.autoRecord(ctx)

    fun isRecording(ctx: Context): Boolean =
        isEnabled(ctx) && CallRecordService.recordingNow

    fun set(ctx: Context, enabled: Boolean) {
        AppSettings.setAutoRecord(ctx, enabled)
        if (!enabled) ctx.stopService(Intent(ctx, CallRecordService::class.java))
    }
}

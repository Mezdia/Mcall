package com.mcall.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.mcall.app.data.AppSettings

/**
 * Wakes the app for the duration of a call and nothing else.
 *
 * This replaces the previous always-on foreground service: between calls no Mcall process
 * is running at all, so there is no background battery cost and no standing notification.
 */
class PhoneStateReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (!AppSettings.autoRecord(ctx)) return

        val fromIntent = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
            else -> TelephonyManager.CALL_STATE_IDLE
        }
        // Broadcasts can lag the real transition, so trust the live state as well.
        val live = runCatching {
            ctx.getSystemService(TelephonyManager::class.java).callState
        }.getOrDefault(TelephonyManager.CALL_STATE_IDLE)

        if (fromIntent == TelephonyManager.CALL_STATE_IDLE && live == TelephonyManager.CALL_STATE_IDLE) return

        val i = Intent(ctx, CallRecordService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (e: Exception) {
            // Usually ForegroundServiceStartNotAllowedException: battery optimization is on.
            Log.w(CallRecordService.TAG, "cannot start during call; set battery usage to Unrestricted", e)
        }
    }
}

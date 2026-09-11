package com.mcall.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** Best-effort restart after reboot when auto-record is enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val auto = ctx.getSharedPreferences("mcall", Context.MODE_PRIVATE)
            .getBoolean("auto_record", true)
        if (!auto) return
        try {
            val s = Intent(ctx, CallRecordService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(s) else ctx.startService(s)
        } catch (e: Exception) {
            Log.w(CallRecordService.TAG, "boot start blocked; open the app once", e)
        }
    }
}

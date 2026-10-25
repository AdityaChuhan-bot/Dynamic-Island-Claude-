package com.aditya.dynamicisland.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || !Settings.canDrawOverlays(context)) return
        val serviceIntent = Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_START)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
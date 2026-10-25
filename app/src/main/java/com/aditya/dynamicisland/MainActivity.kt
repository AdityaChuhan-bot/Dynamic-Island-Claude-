package com.aditya.dynamicisland

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aditya.dynamicisland.service.OverlayService

class MainActivity : AppCompatActivity() {
    private lateinit var tvStatus: TextView
    private lateinit var btnGrantOverlay: Button
    private lateinit var btnGrantListener: Button
    private lateinit var btnToggleIsland: Button
    private var islandRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tvStatus)
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay)
        btnGrantListener = findViewById(R.id.btnGrantListener)
        btnToggleIsland = findViewById(R.id.btnToggleIsland)
        btnGrantOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btnGrantListener.setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        btnToggleIsland.setOnClickListener { if (islandRunning) stopIsland() else startIsland() }
    }

    override fun onResume() { super.onResume(); refreshStatus() }

    private fun refreshStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasListener = isNotificationListenerEnabled()
        tvStatus.text = when {
            !hasOverlay -> getString(R.string.status_missing_overlay)
            !hasListener -> getString(R.string.status_missing_listener)
            islandRunning -> getString(R.string.status_running)
            else -> getString(R.string.status_ready)
        }
        btnToggleIsland.isEnabled = hasOverlay
        btnToggleIsland.text = if (islandRunning) getString(R.string.stop_island) else getString(R.string.start_island)
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return enabled.contains(packageName)
    }

    private fun startIsland() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START))
        islandRunning = true
        refreshStatus()
    }

    private fun stopIsland() {
        startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
        islandRunning = false
        refreshStatus()
    }
}
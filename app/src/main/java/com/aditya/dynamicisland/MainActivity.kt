package com.aditya.dynamicisland

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aditya.dynamicisland.model.IslandContent
import com.aditya.dynamicisland.model.IslandContentType
import com.aditya.dynamicisland.model.IslandRepository
import com.aditya.dynamicisland.service.IslandNotificationListener
import com.aditya.dynamicisland.service.OverlayService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnGrantOverlay: Button
    private lateinit var btnGrantListener: Button
    private lateinit var btnToggleIsland: Button

    private lateinit var btnSimulateNotification: Button
    private lateinit var btnSimulateMusic: Button
    private lateinit var btnSimulateCall: Button
    private lateinit var btnSimulateTimer: Button
    private lateinit var btnToggleExpand: Button
    private lateinit var btnClearContent: Button

    private var isIslandRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay)
        btnGrantListener = findViewById(R.id.btnGrantListener)
        btnToggleIsland = findViewById(R.id.btnToggleIsland)

        btnSimulateNotification = findViewById(R.id.btnSimulateNotification)
        btnSimulateMusic = findViewById(R.id.btnSimulateMusic)
        btnSimulateCall = findViewById(R.id.btnSimulateCall)
        btnSimulateTimer = findViewById(R.id.btnSimulateTimer)
        btnToggleExpand = findViewById(R.id.btnToggleExpand)
        btnClearContent = findViewById(R.id.btnClearContent)

        btnGrantOverlay.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        btnGrantListener.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        btnToggleIsland.setOnClickListener {
            if (isIslandRunning) {
                OverlayService.stop(this)
            } else {
                if (!Settings.canDrawOverlays(this)) {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                    return@setOnClickListener
                }
                OverlayService.start(this)
            }
        }

        btnSimulateNotification.setOnClickListener {
            ensureIslandRunning()
            val sampleIntent = packageManager.getLaunchIntentForPackage("com.google.android.apps.messaging")
                ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)
            val pendingIntent = android.app.PendingIntent.getActivity(
                this,
                99,
                sampleIntent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            IslandRepository.update(
                IslandContent(
                    type = IslandContentType.GENERIC_NOTIFICATION,
                    title = "Sarah Connor",
                    subtitle = "Hey! Are you free for lunch today?",
                    isOngoing = false,
                    packageName = "com.google.android.apps.messaging",
                    appName = "Messages",
                    intent = pendingIntent,
                    notificationKey = "mock_key_messages_123"
                ),
                autoExpand = true
            )
        }

        btnSimulateMusic.setOnClickListener {
            ensureIslandRunning()
            IslandRepository.update(
                IslandContent(
                    type = IslandContentType.MEDIA,
                    title = "Starboy",
                    subtitle = "The Weeknd • Starboy",
                    isOngoing = true,
                    isPlaying = true,
                    progress = 0.45f,
                    packageName = "com.spotify.music",
                    appName = "Spotify"
                )
            )
        }

        btnSimulateCall.setOnClickListener {
            ensureIslandRunning()
            IslandRepository.update(
                IslandContent(
                    type = IslandContentType.CALL,
                    title = "Mom",
                    subtitle = "Incoming Call • 0:14",
                    isOngoing = true,
                    packageName = "com.google.android.dialer",
                    appName = "Phone"
                ),
                autoExpand = true
            )
        }

        btnSimulateTimer.setOnClickListener {
            ensureIslandRunning()
            IslandRepository.update(
                IslandContent(
                    type = IslandContentType.TIMER,
                    title = "Pizza in Oven",
                    subtitle = "04:32 remaining",
                    isOngoing = true,
                    progress = 0.72f,
                    packageName = "com.google.android.deskclock",
                    appName = "Clock"
                )
            )
        }

        btnToggleExpand.setOnClickListener {
            if (isIslandRunning) {
                val intent = Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_TOGGLE)
                startService(intent)
            }
        }

        btnClearContent.setOnClickListener {
            IslandRepository.clear()
        }

        lifecycleScope.launch {
            OverlayService.isRunning.collectLatest { running ->
                isIslandRunning = running
                refreshStatus()
            }
        }

        lifecycleScope.launch {
            IslandNotificationListener.isConnected.collectLatest {
                refreshStatus()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun ensureIslandRunning() {
        if (!isIslandRunning && Settings.canDrawOverlays(this)) {
            OverlayService.start(this)
        }
    }

    private fun refreshStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasListener = isNotificationListenerEnabled()
        val isListenerActive = IslandNotificationListener.isConnected.value

        tvStatus.text = when {
            !hasOverlay -> getString(R.string.status_missing_overlay)
            isIslandRunning && isListenerActive -> "Island is active & listening to notifications"
            isIslandRunning -> getString(R.string.status_running)
            !hasListener -> getString(R.string.status_missing_listener)
            else -> getString(R.string.status_ready)
        }

        btnGrantOverlay.text = if (hasOverlay) "✓ Overlay Permission Granted" else getString(R.string.grant_overlay)
        btnGrantOverlay.isEnabled = !hasOverlay

        btnGrantListener.text = if (isListenerActive) {
            "✓ Notification Access Active"
        } else if (hasListener) {
            "✓ Notification Access Enabled"
        } else {
            getString(R.string.grant_notifications)
        }

        btnToggleIsland.isEnabled = hasOverlay
        btnToggleIsland.text = if (isIslandRunning) getString(R.string.stop_island) else getString(R.string.start_island)

        btnSimulateNotification.isEnabled = hasOverlay
        btnSimulateMusic.isEnabled = hasOverlay
        btnSimulateCall.isEnabled = hasOverlay
        btnSimulateTimer.isEnabled = hasOverlay
        btnToggleExpand.isEnabled = isIslandRunning
        btnClearContent.isEnabled = isIslandRunning
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return enabled.contains(packageName)
    }
}
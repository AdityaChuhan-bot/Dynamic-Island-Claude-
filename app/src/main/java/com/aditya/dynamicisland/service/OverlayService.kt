package com.aditya.dynamicisland.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.aditya.dynamicisland.MainActivity
import com.aditya.dynamicisland.R
import com.aditya.dynamicisland.model.IslandRepository
import com.aditya.dynamicisland.ui.IslandView
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class OverlayService : LifecycleService() {
    private lateinit var windowManager: WindowManager
    private var islandView: IslandView? = null

    companion object {
        const val CHANNEL_ID = "island_service_channel"
        const val NOTIF_ID = 1001
        const val ACTION_START = "com.aditya.dynamicisland.action.START"
        const val ACTION_STOP = "com.aditya.dynamicisland.action.STOP"
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildForegroundNotification())
        addOverlay()
        observeContent()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addOverlay() {
        if (islandView != null) return
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 0
        }
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        val view = IslandView(this)
        windowManager.addView(view, params)
        islandView = view
    }

    private fun observeContent() {
        IslandRepository.content.onEach { islandView?.updateContent(it) }.launchIn(lifecycleScope)
    }

    private fun buildForegroundNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "Dynamic Island service", NotificationManager.IMPORTANCE_MIN).apply { description = "Keeps the floating island running" }
            manager.createNotificationChannel(channel)
        }
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Dynamic Island active")
            .setContentText("Tap to open settings")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    override fun onDestroy() {
        islandView?.let { runCatching { windowManager.removeView(it) } }
        islandView = null
        super.onDestroy()
    }
}
package com.aditya.dynamicisland.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.aditya.dynamicisland.MainActivity
import com.aditya.dynamicisland.R
import com.aditya.dynamicisland.model.IslandRepository
import com.aditya.dynamicisland.ui.IslandView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Foreground Service that manages the WindowManager overlay serving as
 * the interactive base for the Dynamic Island UI element.
 */
class OverlayService : LifecycleService() {

    private lateinit var windowManager: WindowManager
    private var islandView: IslandView? = null

    companion object {
        const val CHANNEL_ID = "island_service_channel"
        const val NOTIF_ID = 1001
        const val ACTION_START = "com.aditya.dynamicisland.action.START"
        const val ACTION_STOP = "com.aditya.dynamicisland.action.STOP"
        const val ACTION_TOGGLE = "com.aditya.dynamicisland.action.TOGGLE"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, OverlayService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun toggle(context: Context) {
            if (_isRunning.value) {
                stop(context)
            } else {
                start(context)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        addOverlay()
        observeContent()
        _isRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                islandView?.toggleExpandCollapse()
            }
            ACTION_START -> {
                if (islandView == null) {
                    addOverlay()
                }
            }
        }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addOverlay() {
        if (islandView != null) return
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val view = IslandView(this).apply {
            setOnOutsideTouchListener {
                if (state == IslandView.IslandState.EXPANDED) {
                    toggleExpandCollapse()
                }
            }
        }

        try {
            windowManager.addView(view, params)
            islandView = view
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun observeContent() {
        IslandRepository.content.onEach { content ->
            islandView?.updateContent(content)
        }.launchIn(lifecycleScope)

        IslandRepository.expandTrigger.onEach { shouldExpand ->
            if (shouldExpand) {
                islandView?.expandTemporarily(4500L)
            }
        }.launchIn(lifecycleScope)
    }

    private fun startForegroundNotification() {
        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildForegroundNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Dynamic Island Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the floating Dynamic Island overlay active"
                setShowBadge(false)
            }
            manager?.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val toggleIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Dynamic Island is Active")
            .setContentText("Tap to open settings • Floating over display cutout")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openIntent)
            .addAction(0, "Expand/Collapse", toggleIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        islandView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        islandView = null
        _isRunning.value = false
        super.onDestroy()
    }
}
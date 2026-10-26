package com.aditya.dynamicisland.service

import android.app.Notification
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.aditya.dynamicisland.model.IslandContent
import com.aditya.dynamicisland.model.IslandContentType
import com.aditya.dynamicisland.model.IslandRepository
import com.aditya.dynamicisland.model.MediaCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * NotificationListenerService that captures incoming system notifications,
 * detects music playback notifications, detects active countdown timers,
 * binds to MediaControllers for real-time controls, and manages dismissals.
 */
class IslandNotificationListener : NotificationListenerService() {

    private var mediaCallback: MediaController.Callback? = null
    private var observedController: MediaController? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isConnected.value = true
        observeDismissTrigger()
        observeMediaCommands()
        refreshMediaSession()
        refreshFromActiveNotifications()
    }

    override fun onListenerDisconnected() {
        _isConnected.value = false
        cleanUpMediaController()
        super.onListenerDisconnected()
    }

    private fun observeDismissTrigger() {
        IslandRepository.dismissTrigger.onEach { key ->
            if (!key.isNullOrBlank()) {
                runCatching { cancelNotification(key) }
            }
            refreshMediaSession()
            refreshFromActiveNotifications()
        }.launchIn(serviceScope)
    }

    private fun observeMediaCommands() {
        IslandRepository.mediaCommand.onEach { cmd ->
            val controller = observedController ?: findActiveMediaController()
            val transport = controller?.transportControls ?: return@onEach
            when (cmd) {
                MediaCommand.PLAY_PAUSE -> {
                    val isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING
                    if (isPlaying) {
                        transport.pause()
                    } else {
                        transport.play()
                    }
                }
                MediaCommand.NEXT -> {
                    transport.skipToNext()
                }
                MediaCommand.PREVIOUS -> {
                    transport.skipToPrevious()
                }
            }
        }.launchIn(serviceScope)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // 1. Never capture notifications posted by our own application
        if (sbn.packageName == packageName) return

        val notif = sbn.notification ?: return

        // 2. Ensure the overlay service is started if overlay permission is granted
        if (!OverlayService.isRunning.value && Settings.canDrawOverlays(this)) {
            OverlayService.start(this)
        }

        // 3. Check if this is a music/media playback notification
        val isMediaSessionTokenPresent = runCatching {
            @Suppress("DEPRECATION")
            notif.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION) != null ||
                    notif.extras.get("android.mediaSession") != null
        }.getOrNull() == true

        val isMediaCategory = notif.category == Notification.CATEGORY_TRANSPORT
        val isMediaPackage = sbn.packageName.contains("music") ||
                sbn.packageName.contains("spotify") ||
                sbn.packageName.contains("audio") ||
                sbn.packageName.contains("podcast")

        if (isMediaSessionTokenPresent || (isMediaCategory && isMediaPackage)) {
            val token = runCatching {
                @Suppress("DEPRECATION")
                notif.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
            }.getOrNull()

            if (token != null) {
                val controller = MediaController(this, token)
                attachMediaController(controller)
            } else {
                refreshMediaSession()
            }

            val controller = observedController ?: findActiveMediaController()
            if (controller != null) {
                val metadata = controller.metadata
                val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().ifBlank {
                    notif.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty().ifBlank { "Now Playing" }
                }
                val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty().ifBlank {
                    notif.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
                }
                val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                val position = controller.playbackState?.position ?: 0L
                val progress = if (duration > 0) position.toFloat() / duration.toFloat() else null
                val isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING

                val appName = resolveAppName(sbn.packageName)

                val mediaContent = IslandContent(
                    type = IslandContentType.MEDIA,
                    title = title,
                    subtitle = artist,
                    isOngoing = true,
                    isPlaying = isPlaying,
                    progress = progress,
                    packageName = sbn.packageName,
                    appName = appName,
                    intent = notif.contentIntent,
                    notificationKey = sbn.key,
                    timestamp = sbn.postTime
                )

                IslandRepository.update(mediaContent, autoExpand = false)
                return
            }
        }

        // 4. Extract text fields
        val isGroupSummary = (notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0

        val rawTitle = notif.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = notif.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = notif.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val subText = notif.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        val ticker = notif.tickerText?.toString()?.trim().orEmpty()

        val rawBody = when {
            bigText.isNotBlank() -> bigText
            text.isNotBlank() -> text
            subText.isNotBlank() -> subText
            ticker.isNotBlank() -> ticker
            else -> ""
        }

        if (rawTitle.isBlank() && rawBody.isBlank()) {
            refreshMediaSession()
            return
        }

        if (isGroupSummary && rawBody.isBlank()) return

        val appName = resolveAppName(sbn.packageName)
        val displayTitle = if (rawTitle.isNotBlank()) rawTitle else appName
        val displaySubtitle = if (rawBody.isNotBlank()) rawBody else appName
        val isOngoing = (notif.flags and Notification.FLAG_ONGOING_EVENT) != 0

        // 5. Check if this is an active countdown timer or stopwatch
        val isCountDownChronometer = notif.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)
        val isTimerCategory = notif.category == Notification.CATEGORY_ALARM ||
                notif.category == Notification.CATEGORY_STOPWATCH
        val isTimerPackage = sbn.packageName.contains("clock") ||
                sbn.packageName.contains("timer") ||
                sbn.packageName.contains("deskclock")

        if (isCountDownChronometer || isTimerCategory || (isTimerPackage && isOngoing)) {
            val (targetEndTime, totalSecs) = extractTimerCountdown(notif, "$rawTitle $rawBody $ticker")

            val timerContent = IslandContent(
                type = IslandContentType.TIMER,
                title = if (rawTitle.isNotBlank() && !rawTitle.contains(":")) rawTitle else "Timer",
                subtitle = if (rawBody.isNotBlank()) rawBody else "Countdown",
                isOngoing = true,
                packageName = sbn.packageName,
                appName = appName,
                intent = notif.contentIntent,
                notificationKey = sbn.key,
                timestamp = sbn.postTime,
                targetEndTime = targetEndTime,
                totalDurationSeconds = totalSecs
            )

            IslandRepository.update(timerContent, autoExpand = true)
            return
        }

        // 6. Incoming Phone Call
        val isCall = notif.category == Notification.CATEGORY_CALL
        if (isCall) {
            val callContent = IslandContent(
                type = IslandContentType.CALL,
                title = displayTitle,
                subtitle = displaySubtitle,
                isOngoing = isOngoing,
                packageName = sbn.packageName,
                appName = appName,
                intent = notif.contentIntent,
                notificationKey = sbn.key,
                timestamp = sbn.postTime
            )
            IslandRepository.update(callContent, autoExpand = true)
            return
        }

        // 7. Generic Notification
        val content = IslandContent(
            type = IslandContentType.GENERIC_NOTIFICATION,
            title = displayTitle,
            subtitle = displaySubtitle,
            isOngoing = isOngoing,
            packageName = sbn.packageName,
            appName = appName,
            intent = notif.contentIntent,
            notificationKey = sbn.key,
            timestamp = sbn.postTime
        )

        IslandRepository.update(content, autoExpand = true)
    }

    private fun extractTimerCountdown(notif: Notification, textToSearch: String): Pair<Long?, Long?> {
        var targetEndTime: Long? = null
        var totalDurationSeconds: Long? = null

        // 1. Check if 'when' contains future target epoch millis
        if (notif.`when` > System.currentTimeMillis()) {
            val diffSecs = (notif.`when` - System.currentTimeMillis()) / 1000L
            if (diffSecs in 1..86400) {
                targetEndTime = notif.`when`
                totalDurationSeconds = diffSecs
            }
        }

        // 2. Fallback: Parse digits like MM:SS or HH:MM:SS from text
        if (targetEndTime == null) {
            val timeRegex = Regex("""(\d{1,2}):(\d{2})(?::(\d{2}))?""")
            val match = timeRegex.find(textToSearch)
            if (match != null) {
                val parts = match.value.split(":")
                val secs = when (parts.size) {
                    3 -> (parts[0].toLongOrNull() ?: 0L) * 3600L +
                            (parts[1].toLongOrNull() ?: 0L) * 60L +
                            (parts[2].toLongOrNull() ?: 0L)
                    2 -> (parts[0].toLongOrNull() ?: 0L) * 60L + (parts[1].toLongOrNull() ?: 0L)
                    else -> 0L
                }
                if (secs > 0) {
                    targetEndTime = System.currentTimeMillis() + secs * 1000L
                    totalDurationSeconds = secs
                }
            }
        }

        return targetEndTime to totalDurationSeconds
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return

        val current = IslandRepository.content.value
        if (current.notificationKey == sbn.key || (current.packageName == sbn.packageName && current.type != IslandContentType.MEDIA)) {
            refreshMediaSession()
            refreshFromActiveNotifications()
        }
    }

    private fun resolveAppName(packageName: String): String {
        return runCatching {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        }.getOrNull() ?: packageName
    }

    private fun refreshMediaSession() {
        val active = findActiveMediaController()
        if (active?.sessionToken != observedController?.sessionToken) {
            cleanUpMediaController()
            if (active != null) {
                attachMediaController(active)
            }
        }
    }

    private fun findActiveMediaController(): MediaController? {
        val msm = getSystemService(MediaSessionManager::class.java) ?: return null
        val componentName = ComponentName(this, IslandNotificationListener::class.java)
        val controllers = runCatching { msm.getActiveSessions(componentName) }.getOrNull().orEmpty()
        return controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull()
    }

    private fun attachMediaController(controller: MediaController) {
        if (observedController?.sessionToken == controller.sessionToken) return
        cleanUpMediaController()
        observedController = controller

        val cb = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) = refreshFromActiveNotifications()
            override fun onMetadataChanged(metadata: MediaMetadata?) = refreshFromActiveNotifications()
            override fun onSessionDestroyed() {
                cleanUpMediaController()
                refreshFromActiveNotifications()
            }
        }
        controller.registerCallback(cb)
        mediaCallback = cb
    }

    private fun cleanUpMediaController() {
        val old = observedController
        val callback = mediaCallback
        if (old != null && callback != null) {
            runCatching { old.unregisterCallback(callback) }
        }
        observedController = null
        mediaCallback = null
    }

    private fun refreshFromActiveNotifications() {
        // Priority 1: Currently playing media
        val controller = observedController ?: findActiveMediaController()
        if (controller != null && controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
            val metadata = controller.metadata
            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().ifBlank { "Playing Audio" }
            val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
            val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val position = controller.playbackState?.position ?: 0L
            val progress = if (duration > 0) position.toFloat() / duration.toFloat() else null
            val isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING

            val appName = resolveAppName(controller.packageName)

            IslandRepository.update(
                IslandContent(
                    type = IslandContentType.MEDIA,
                    title = title,
                    subtitle = artist,
                    isOngoing = true,
                    isPlaying = isPlaying,
                    progress = progress,
                    packageName = controller.packageName,
                    appName = appName
                ),
                autoExpand = false
            )
            return
        }

        // Priority 2: Active incoming call
        val active = runCatching { activeNotifications }.getOrNull() ?: emptyArray()
        val call = active.firstOrNull { it.notification?.category == Notification.CATEGORY_CALL && it.packageName != packageName }
        if (call != null) {
            val extras = call.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "Incoming Call"
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            IslandRepository.update(
                IslandContent(
                    type = IslandContentType.CALL,
                    title = title,
                    subtitle = text,
                    isOngoing = true,
                    packageName = call.packageName,
                    intent = call.notification.contentIntent,
                    notificationKey = call.key
                ),
                autoExpand = false
            )
            return
        }

        // Priority 3: Active timer or stopwatch
        val timer = active.firstOrNull {
            it.packageName != packageName &&
                    (it.packageName.contains("deskclock") || it.packageName.contains("clock") || it.packageName.contains("timer")) &&
                    it.isOngoing
        }
        if (timer != null) {
            val notif = timer.notification
            val extras = notif.extras
            val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            val ticker = notif.tickerText?.toString().orEmpty()
            val (targetEndTime, totalSecs) = extractTimerCountdown(notif, "$rawTitle $rawText $ticker")

            IslandRepository.update(
                IslandContent(
                    type = IslandContentType.TIMER,
                    title = if (rawTitle.isNotBlank() && !rawTitle.contains(":")) rawTitle else "Timer",
                    subtitle = if (rawText.isNotBlank()) rawText else "Countdown",
                    isOngoing = true,
                    packageName = timer.packageName,
                    appName = resolveAppName(timer.packageName),
                    intent = notif.contentIntent,
                    notificationKey = timer.key,
                    targetEndTime = targetEndTime,
                    totalDurationSeconds = totalSecs
                ),
                autoExpand = false
            )
            return
        }

        // If no ongoing notifications, clear
        if (IslandRepository.content.value.isOngoing) {
            IslandRepository.clear()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        cleanUpMediaController()
        _isConnected.value = false
        super.onDestroy()
    }
}
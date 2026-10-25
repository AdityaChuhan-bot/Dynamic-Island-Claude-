package com.aditya.dynamicisland.service

import android.app.Notification
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.aditya.dynamicisland.model.IslandContent
import com.aditya.dynamicisland.model.IslandContentType
import com.aditya.dynamicisland.model.IslandRepository

class IslandNotificationListener : NotificationListenerService() {
    private var mediaCallback: MediaController.Callback? = null
    private var observedController: MediaController? = null

    override fun onListenerConnected() { super.onListenerConnected(); refreshMediaSession(); refreshFromActiveNotifications() }
    override fun onNotificationPosted(sbn: StatusBarNotification) { refreshMediaSession(); refreshFromActiveNotifications() }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { refreshMediaSession(); refreshFromActiveNotifications() }

    private fun refreshMediaSession() {
        val msm = getSystemService(MediaSessionManager::class.java) ?: return
        val componentName = android.content.ComponentName(this, IslandNotificationListener::class.java)
        val controllers = runCatching { msm.getActiveSessions(componentName) }.getOrNull().orEmpty()
        val active = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: controllers.firstOrNull()
        if (active?.sessionToken != observedController?.sessionToken) {
            val old = observedController
            val callback = mediaCallback
            if (old != null && callback != null) old.unregisterCallback(callback)
            observedController = active
            mediaCallback = null
            if (active != null) {
                val cb = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) = refreshFromActiveNotifications()
                    override fun onMetadataChanged(metadata: android.media.MediaMetadata?) = refreshFromActiveNotifications()
                }
                active.registerCallback(cb)
                mediaCallback = cb
            }
        }
    }

    private fun refreshFromActiveNotifications() {
        val controller = observedController
        if (controller != null && controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
            val metadata = controller.metadata
            val title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            val artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
            val duration = metadata?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val position = controller.playbackState?.position ?: 0L
            val progress = if (duration > 0) position.toFloat() / duration.toFloat() else null
            IslandRepository.update(IslandContent(type = IslandContentType.MEDIA, title = title, subtitle = artist, isOngoing = true, isPlaying = true, progress = progress, packageName = controller.packageName))
            return
        }
        val active = activeNotifications ?: emptyArray()
        val call = active.firstOrNull { it.notification.category == Notification.CATEGORY_CALL }
        if (call != null) {
            val extras = call.notification.extras
            IslandRepository.update(IslandContent(type = IslandContentType.CALL, title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "Call", subtitle = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(), isOngoing = true, packageName = call.packageName))
            return
        }
        val timer = active.firstOrNull { (it.packageName.contains("deskclock") || it.packageName.contains("clock")) && it.isOngoing }
        if (timer != null) {
            val extras = timer.notification.extras
            IslandRepository.update(IslandContent(type = IslandContentType.TIMER, title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "Timer", subtitle = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(), isOngoing = true, packageName = timer.packageName))
            return
        }
        IslandRepository.clear()
    }
}
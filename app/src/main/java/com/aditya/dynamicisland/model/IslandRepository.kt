package com.aditya.dynamicisland.model

import android.app.PendingIntent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class IslandContentType {
    NONE,
    MEDIA,
    CALL,
    TIMER,
    GENERIC_NOTIFICATION
}

enum class MediaCommand {
    PLAY_PAUSE,
    NEXT,
    PREVIOUS
}

data class IslandContent(
    val type: IslandContentType = IslandContentType.NONE,
    val title: String = "",
    val subtitle: String = "",
    val isOngoing: Boolean = false,
    val progress: Float? = null,
    val isPlaying: Boolean = false,
    val packageName: String? = null,
    val appName: String = "",
    val intent: PendingIntent? = null,
    val notificationKey: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

object IslandRepository {
    private val _content = MutableStateFlow(IslandContent())
    val content: StateFlow<IslandContent> = _content.asStateFlow()

    private val _expandTrigger = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val expandTrigger: SharedFlow<Boolean> = _expandTrigger.asSharedFlow()

    private val _dismissTrigger = MutableSharedFlow<String?>(extraBufferCapacity = 1)
    val dismissTrigger: SharedFlow<String?> = _dismissTrigger.asSharedFlow()

    private val _mediaCommand = MutableSharedFlow<MediaCommand>(extraBufferCapacity = 1)
    val mediaCommand: SharedFlow<MediaCommand> = _mediaCommand.asSharedFlow()

    private val sampleTracks = listOf(
        "Starboy" to "The Weeknd • Starboy",
        "Blinding Lights" to "The Weeknd • After Hours",
        "Save Your Tears" to "The Weeknd • After Hours",
        "Levitating" to "Dua Lipa • Future Nostalgia",
        "Midnight City" to "M83 • Hurry Up, We're Dreaming"
    )

    fun update(content: IslandContent, autoExpand: Boolean = false) {
        _content.value = content
        if (autoExpand) {
            _expandTrigger.tryEmit(true)
        }
    }

    fun triggerExpansion() {
        _expandTrigger.tryEmit(true)
    }

    fun sendMediaCommand(cmd: MediaCommand) {
        _mediaCommand.tryEmit(cmd)

        // Optimistically update current state so UI responds with zero lag
        val current = _content.value
        if (current.type == IslandContentType.MEDIA) {
            when (cmd) {
                MediaCommand.PLAY_PAUSE -> {
                    _content.value = current.copy(isPlaying = !current.isPlaying)
                }
                MediaCommand.NEXT -> {
                    val nextTrack = getNextTrack(current.title)
                    _content.value = current.copy(
                        title = nextTrack.first,
                        subtitle = nextTrack.second,
                        progress = 0.05f,
                        isPlaying = true
                    )
                }
                MediaCommand.PREVIOUS -> {
                    val prevTrack = getPrevTrack(current.title)
                    _content.value = current.copy(
                        title = prevTrack.first,
                        subtitle = prevTrack.second,
                        progress = 0.05f,
                        isPlaying = true
                    )
                }
            }
        }
    }

    private fun getNextTrack(currentTitle: String): Pair<String, String> {
        val idx = sampleTracks.indexOfFirst { it.first.equals(currentTitle, ignoreCase = true) }
        val nextIdx = if (idx >= 0) (idx + 1) % sampleTracks.size else 0
        return sampleTracks[nextIdx]
    }

    private fun getPrevTrack(currentTitle: String): Pair<String, String> {
        val idx = sampleTracks.indexOfFirst { it.first.equals(currentTitle, ignoreCase = true) }
        val prevIdx = if (idx > 0) idx - 1 else sampleTracks.size - 1
        return sampleTracks[prevIdx]
    }

    fun dismissCurrent() {
        val currentKey = _content.value.notificationKey
        _dismissTrigger.tryEmit(currentKey)
        _content.value = IslandContent()
    }

    fun clear() {
        _content.value = IslandContent()
    }
}
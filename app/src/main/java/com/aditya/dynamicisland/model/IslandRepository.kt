package com.aditya.dynamicisland.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class IslandContentType { NONE, MEDIA, CALL, TIMER, GENERIC_NOTIFICATION }

data class IslandContent(
    val type: IslandContentType = IslandContentType.NONE,
    val title: String = "",
    val subtitle: String = "",
    val isOngoing: Boolean = false,
    val progress: Float? = null,
    val isPlaying: Boolean = false,
    val packageName: String? = null
)

object IslandRepository {
    private val _content = MutableStateFlow(IslandContent())
    val content: StateFlow<IslandContent> = _content.asStateFlow()
    fun update(content: IslandContent) { _content.value = content }
    fun clear() { _content.value = IslandContent() }
}
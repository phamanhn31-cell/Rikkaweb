package me.rerere.rikkahub.standalone.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class PagedResult<T>(
    val items: List<T>,
    val nextOffset: Int? = null,
    val hasMore: Boolean,
)

@Serializable
data class ConversationListDto(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val isGenerating: Boolean,
)

@Serializable
data class ConversationDto(
    val id: String,
    val assistantId: String,
    val title: String,
    val messages: List<MessageNodeDto>,
    val truncateIndex: Int,
    val chatSuggestions: List<String>,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val isGenerating: Boolean,
)

@Serializable
data class MessageNodeDto(
    val id: String,
    val messages: List<MessageDto>,
    val selectIndex: Int,
)

@Serializable
data class MessageDto(
    val id: String,
    val role: String,
    val parts: List<JsonElement>,
    val annotations: List<JsonElement> = emptyList(),
    val createdAt: String,
    val finishedAt: String? = null,
    val modelId: String? = null,
    val usage: JsonElement? = null,
    val translation: String? = null,
)

@Serializable
data class ConversationListInvalidateEventDto(
    val type: String,
    val assistantId: String,
    val timestamp: Long,
)

@Serializable
data class MessageSearchResultDto(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val title: String,
    val updateAt: Long,
    val snippet: String,
)

@Serializable
data class ConversationSnapshotEventDto(
    val type: String,
    val seq: Long,
    val conversation: ConversationDto,
    val serverTime: Long,
)

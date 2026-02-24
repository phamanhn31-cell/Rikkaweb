package me.rerere.rikkahub.standalone.backup

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object SettingsJsonMigrator {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun migrate(settingsJson: String): String {
        return runCatching {
            val element = json.parseToJsonElement(settingsJson)
            val root = (element as? JsonObject)?.toMutableMap() ?: return@runCatching settingsJson

            root["mcpServers"]?.let { mcpServers ->
                val migrated = migrateMcpServersJson(json.encodeToString(mcpServers))
                root["mcpServers"] = json.parseToJsonElement(migrated)
            }

            root["assistants"]?.let { assistants ->
                val migrated = migrateAssistantsJson(json.encodeToString(assistants))
                root["assistants"] = json.parseToJsonElement(migrated)
            }

            json.encodeToString(JsonObject(root))
        }.getOrElse { settingsJson }
    }

    private fun migrateMcpServersJson(jsonText: String): String {
        val element = json.parseToJsonElement(jsonText).jsonArray.map { elementItem ->
            val jsonObj = elementItem.jsonObject.toMutableMap()
            val type = jsonObj["type"]?.jsonPrimitive?.content ?: ""
            when (type) {
                "me.rerere.rikkahub.data.mcp.McpServerConfig.SseTransportServer" -> {
                    jsonObj["type"] = JsonPrimitive("sse")
                }

                "me.rerere.rikkahub.data.mcp.McpServerConfig.StreamableHTTPServer" -> {
                    jsonObj["type"] = JsonPrimitive("streamable_http")
                }
            }
            JsonObject(jsonObj)
        }
        return json.encodeToString(element)
    }

    private val partTypeMapping = mapOf(
        "Text" to "text",
        "UIMessagePart.Text" to "text",
        "me.rerere.ai.ui.UIMessagePart.Text" to "text",
        "Image" to "image",
        "UIMessagePart.Image" to "image",
        "me.rerere.ai.ui.UIMessagePart.Image" to "image",
        "Video" to "video",
        "UIMessagePart.Video" to "video",
        "me.rerere.ai.ui.UIMessagePart.Video" to "video",
        "Audio" to "audio",
        "UIMessagePart.Audio" to "audio",
        "me.rerere.ai.ui.UIMessagePart.Audio" to "audio",
        "Document" to "document",
        "UIMessagePart.Document" to "document",
        "me.rerere.ai.ui.UIMessagePart.Document" to "document",
        "Reasoning" to "reasoning",
        "UIMessagePart.Reasoning" to "reasoning",
        "me.rerere.ai.ui.UIMessagePart.Reasoning" to "reasoning",
        "Search" to "search",
        "UIMessagePart.Search" to "search",
        "me.rerere.ai.ui.UIMessagePart.Search" to "search",
        "ToolCall" to "tool_call",
        "UIMessagePart.ToolCall" to "tool_call",
        "me.rerere.ai.ui.UIMessagePart.ToolCall" to "tool_call",
        "ToolResult" to "tool_result",
        "UIMessagePart.ToolResult" to "tool_result",
        "me.rerere.ai.ui.UIMessagePart.ToolResult" to "tool_result",
        "Tool" to "tool",
        "UIMessagePart.Tool" to "tool",
        "me.rerere.ai.ui.UIMessagePart.Tool" to "tool",
    )

    private fun migrateAssistantsJson(assistantsJson: String): String {
        return runCatching {
            val element = json.parseToJsonElement(assistantsJson)
            val root = element as? JsonArray ?: return@runCatching assistantsJson
            val migrated = JsonArray(
                root.map { assistant ->
                    val assistantObj = assistant as? JsonObject ?: return@map assistant

                    val assistantMap = assistantObj.toMutableMap()
                    val legacyMcp = assistantMap["mcpServerIds"]
                    if (assistantMap["mcpServers"] == null && legacyMcp is JsonArray) {
                        assistantMap["mcpServers"] = legacyMcp
                    }
                    assistantMap.remove("mcpServerIds")

                    val migratedAssistant = JsonObject(assistantMap)

                    val presetMessages = assistantObj["presetMessages"] as? JsonArray ?: return@map assistant
                    val migratedPresetMessages = JsonArray(
                        presetMessages.map { message ->
                            val messageObj = message as? JsonObject ?: return@map message
                            val parts = messageObj["parts"] as? JsonArray ?: return@map message
                            val migratedParts = migratePartsArray(parts)
                            if (migratedParts == parts) {
                                message
                            } else {
                                JsonObject(messageObj.toMutableMap().apply {
                                    put("parts", migratedParts)
                                })
                            }
                        }
                    )
                    if (migratedPresetMessages == presetMessages) {
                        migratedAssistant
                    } else {
                        JsonObject((migratedAssistant as JsonObject).toMutableMap().apply {
                            put("presetMessages", migratedPresetMessages)
                        })
                    }
                }
            )
            if (migrated == root) assistantsJson else json.encodeToString(migrated)
        }.getOrElse { assistantsJson }
    }

    private fun migratePartsArray(parts: JsonArray): JsonArray {
        return JsonArray(
            parts.map { part ->
                val partObj = part as? JsonObject ?: return@map part
                val typeValue = partObj["type"]?.jsonPrimitiveOrNull()?.contentOrNull
                val mappedType = typeValue?.let { partTypeMapping[it] } ?: typeValue

                val updatedPart = if (mappedType != null && mappedType != typeValue) {
                    JsonObject(partObj.toMutableMap().apply {
                        put("type", JsonPrimitive(mappedType))
                    })
                } else {
                    partObj
                }

                val output = updatedPart["output"] as? JsonArray ?: return@map updatedPart
                val migratedOutput = migratePartsArray(output)
                if (migratedOutput == output) {
                    updatedPart
                } else {
                    JsonObject(updatedPart.toMutableMap().apply {
                        put("output", migratedOutput)
                    })
                }
            }
        )
    }
}

private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? {
    return this as? JsonPrimitive
}

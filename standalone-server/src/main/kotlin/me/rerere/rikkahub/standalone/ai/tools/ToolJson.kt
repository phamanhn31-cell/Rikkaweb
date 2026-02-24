package me.rerere.rikkahub.standalone.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object ToolJson {
    fun textPart(text: String): JsonObject {
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive("text"),
                "text" to JsonPrimitive(text),
            )
        )
    }

    fun imagePart(url: String): JsonObject {
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive("image"),
                "url" to JsonPrimitive(url),
            )
        )
    }

    fun toolPart(
        toolCallId: String,
        toolName: String,
        inputJson: String,
        approvalType: String,
        approvalReason: String? = null,
        outputParts: List<JsonObject> = emptyList(),
    ): JsonObject {
        val approval = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "type" to JsonPrimitive(approvalType),
        )
        if (!approvalReason.isNullOrBlank()) {
            approval["reason"] = JsonPrimitive(approvalReason)
        }
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive("tool"),
                "toolCallId" to JsonPrimitive(toolCallId),
                "toolName" to JsonPrimitive(toolName),
                "input" to JsonPrimitive(inputJson),
                "output" to JsonArray(outputParts),
                "approvalState" to JsonObject(approval),
                "progress" to JsonNull,
            )
        )
    }
}

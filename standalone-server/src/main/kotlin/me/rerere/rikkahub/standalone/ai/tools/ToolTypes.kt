package me.rerere.rikkahub.standalone.ai.tools

import kotlinx.serialization.json.JsonObject

data class ToolSpec(
    val name: String,
    val description: String?,
    val inputSchema: JsonObject,
    val needsApproval: Boolean,
)

data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class ToolExecutionResult(
    val outputParts: List<JsonObject>,
    val modelText: String,
)

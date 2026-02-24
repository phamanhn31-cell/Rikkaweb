package me.rerere.rikkahub.standalone.ai

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.standalone.ai.tools.ToolCall
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ClaudeStreamingClient(private val client: OkHttpClient) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    data class Params(
        val baseUrl: String,
        val apiKey: String,
        val remoteModelId: String,
        val system: String,
        val messages: JsonArray,
        val temperature: Double?,
        val topP: Double?,
        val maxTokens: Int?,
        val tools: JsonArray = JsonArray(emptyList()),
    )

    data class StreamResult(
        val toolCalls: List<ToolCall>,
    )

    fun streamText(params: Params, onDelta: (String) -> Unit) {
        streamChat(params, onDelta)
    }

    fun streamChat(params: Params, onDelta: (String) -> Unit): StreamResult {
        val toolCallsByIndex = LinkedHashMap<Int, ToolUseBuilder>()
        val url = buildUrl(params.baseUrl, "/messages")
        val bodyJson = buildBody(params)

        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", params.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Accept", "text/event-stream")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string().orEmpty()
                throw IllegalStateException("Claude request failed: HTTP ${response.code} ${err.take(300)}")
            }
            val body = response.body ?: throw IllegalStateException("Claude response body is empty")
            BufferedReader(InputStreamReader(body.byteStream(), StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    val element = runCatching { json.parseToJsonElement(data) as JsonObject }.getOrNull() ?: continue

                    when (val type = element["type"]?.jsonPrimitive?.contentOrNull) {
                        "message_stop" -> {
                            return StreamResult(
                                toolCalls = toolCallsByIndex.entries
                                    .sortedBy { it.key }
                                    .map { (_, b) -> b.build(json) },
                            )
                        }
                        "error" -> {
                            val msg = element["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                                ?: "Claude error"
                            throw IllegalStateException(msg)
                        }
                        "content_block_delta" -> {
                            val delta = element["delta"]?.jsonObject
                            val deltaType = delta?.get("type")?.jsonPrimitive?.contentOrNull
                            when (deltaType) {
                                "text_delta" -> {
                                    val text = delta.get("text")?.jsonPrimitive?.contentOrNull
                                    if (!text.isNullOrEmpty()) onDelta(text)
                                }

                                "input_json_delta" -> {
                                    val index = element["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                                    if (index != null) {
                                        val builder = toolCallsByIndex.getOrPut(index) { ToolUseBuilder(index) }
                                        val piece = delta.get("partial_json")?.jsonPrimitive?.contentOrNull
                                        if (!piece.isNullOrEmpty()) builder.appendJson(piece)
                                    }
                                }

                                else -> Unit
                            }
                        }
                        "content_block_start" -> {
                            val content = element["content_block"]?.jsonObject
                            val index = element["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                            val contentType = content?.get("type")?.jsonPrimitive?.contentOrNull
                            when (contentType) {
                                "text" -> {
                                    val text = content.get("text")?.jsonPrimitive?.contentOrNull
                                    if (!text.isNullOrEmpty()) onDelta(text)
                                }

                                "tool_use" -> {
                                    if (index != null) {
                                        val builder = toolCallsByIndex.getOrPut(index) { ToolUseBuilder(index) }
                                        builder.id = content.get("id")?.jsonPrimitive?.contentOrNull ?: builder.id
                                        builder.name = content.get("name")?.jsonPrimitive?.contentOrNull ?: builder.name
                                        val input = content["input"]
                                        if (input != null) {
                                            builder.setInputElement(input)
                                        }
                                    }
                                }

                                else -> Unit
                            }
                        }

                        else -> Unit
                    }
                }
            }
        }
        return StreamResult(
            toolCalls = toolCallsByIndex.entries
                .sortedBy { it.key }
                .map { (_, b) -> b.build(json) },
        )
    }

    private fun buildBody(params: Params): String {
        val root = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "model" to JsonPrimitive(params.remoteModelId),
            "system" to JsonPrimitive(params.system),
            "messages" to params.messages,
            "stream" to JsonPrimitive(true),
        )
        params.temperature?.let { root["temperature"] = JsonPrimitive(it) }
        params.topP?.let { root["top_p"] = JsonPrimitive(it) }
        params.maxTokens?.let { root["max_tokens"] = JsonPrimitive(it) }
        if (params.maxTokens == null) {
            root["max_tokens"] = JsonPrimitive(2048)
        }
        if (params.tools.isNotEmpty()) {
            root["tools"] = params.tools
        }
        return json.encodeToString(JsonObject(root))
    }

    private fun buildUrl(baseUrl: String, path: String): String {
        val b = baseUrl.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        return b + p
    }
}

private class ToolUseBuilder(private val index: Int) {
    var id: String? = null
    var name: String? = null
    private val jsonPieces = StringBuilder()
    private var inputElement: JsonElement? = null

    fun setInputElement(el: JsonElement) {
        inputElement = el
    }

    fun appendJson(piece: String) {
        jsonPieces.append(piece)
    }

    fun build(json: Json): ToolCall {
        val callId = id ?: "tool-$index"
        val toolName = name ?: ""
        val args = when {
            inputElement != null -> json.encodeToString(JsonElement.serializer(), inputElement!!)
            jsonPieces.isNotEmpty() -> jsonPieces.toString()
            else -> "{}"
        }
        return ToolCall(id = callId, name = toolName, argumentsJson = args)
    }
}

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
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.standalone.ai.tools.ToolCall
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OpenAiStreamingClient(private val client: OkHttpClient) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    data class Params(
        val baseUrl: String,
        val chatCompletionsPath: String,
        val apiKey: String,
        val remoteModelId: String,
        val messages: JsonArray,
        val temperature: Double?,
        val topP: Double?,
        val maxTokens: Int?,
        val tools: JsonArray = JsonArray(emptyList()),
    )

    data class StreamResult(
        val toolCalls: List<ToolCall>,
        val finishReason: String?,
    )

    fun streamText(params: Params, onDelta: (String) -> Unit) {
        streamChat(params, onDelta)
    }

    fun streamChat(params: Params, onDelta: (String) -> Unit): StreamResult {
        val url = buildUrl(params.baseUrl, params.chatCompletionsPath)

        val bodyJson = buildChatCompletionsBody(params)
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${params.apiKey}")
            .addHeader("Accept", "text/event-stream")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string().orEmpty()
                throw IllegalStateException("OpenAI request failed: HTTP ${response.code} ${err.take(300)}")
            }
            val body = response.body ?: throw IllegalStateException("OpenAI response body is empty")
            BufferedReader(InputStreamReader(body.byteStream(), StandardCharsets.UTF_8)).use { reader ->
                val toolCallsByIndex = LinkedHashMap<Int, ToolCallBuilder>()
                var finishReason: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") {
                        return StreamResult(
                            toolCalls = toolCallsByIndex.entries
                                .sortedBy { it.key }
                                .map { (_, b) -> b.build() },
                            finishReason = finishReason,
                        )
                    }
                    if (data.isEmpty()) continue

                    val element = runCatching { json.parseToJsonElement(data) as JsonObject }.getOrNull() ?: continue
                    val choice0 = element["choices"]
                        ?.jsonArrayOrNull()
                        ?.getOrNull(0)
                        ?.jsonObjectOrNull()
                    finishReason = choice0?.get("finish_reason")?.jsonPrimitive?.contentOrNull ?: finishReason
                    val delta = choice0?.get("delta")?.jsonObjectOrNull()

                    val content = delta?.get("content")?.jsonPrimitive?.contentOrNull
                    if (!content.isNullOrEmpty()) {
                        onDelta(content)
                    }

                    val toolCalls = delta?.get("tool_calls")?.jsonArrayOrNull()
                    if (toolCalls != null) {
                        toolCalls.forEachIndexed { fallbackIndex, tcEl ->
                            val tc = tcEl as? JsonObject ?: return@forEachIndexed
                            val index = tc["index"]?.jsonPrimitive?.intOrNull ?: fallbackIndex
                            val builder = toolCallsByIndex.getOrPut(index) { ToolCallBuilder(index = index) }
                            builder.ingest(tc)
                        }
                    }
                }

                return StreamResult(
                    toolCalls = toolCallsByIndex.entries
                        .sortedBy { it.key }
                        .map { (_, b) -> b.build() },
                    finishReason = finishReason,
                )
            }
        }
    }

    private fun buildChatCompletionsBody(params: Params): String {
        val root = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "model" to JsonPrimitive(params.remoteModelId),
            "messages" to params.messages,
            "stream" to JsonPrimitive(true),
        )
        params.temperature?.let { root["temperature"] = JsonPrimitive(it) }
        params.topP?.let { root["top_p"] = JsonPrimitive(it) }
        params.maxTokens?.let { root["max_tokens"] = JsonPrimitive(it) }
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

private class ToolCallBuilder(private val index: Int) {
    private var id: String? = null
    private var name: String? = null
    private val args = StringBuilder()

    fun ingest(obj: JsonObject) {
        id = obj["id"]?.jsonPrimitive?.contentOrNull ?: id
        val fn = obj["function"] as? JsonObject
        name = fn?.get("name")?.jsonPrimitive?.contentOrNull ?: name
        val piece = fn?.get("arguments")?.jsonPrimitive?.contentOrNull
        if (!piece.isNullOrEmpty()) args.append(piece)
    }

    fun build(): ToolCall {
        val callId = id ?: "tool-$index"
        val toolName = name ?: ""
        return ToolCall(
            id = callId,
            name = toolName,
            argumentsJson = args.toString(),
        )
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonArrayOrNull(): kotlinx.serialization.json.JsonArray? {
    return this as? kotlinx.serialization.json.JsonArray
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): kotlinx.serialization.json.JsonObject? {
    return this as? kotlinx.serialization.json.JsonObject
}

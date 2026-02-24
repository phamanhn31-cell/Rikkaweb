package me.rerere.rikkahub.standalone.ai

import java.nio.file.Path
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import me.rerere.rikkahub.standalone.db.withSqliteConnection
import me.rerere.rikkahub.standalone.ai.tools.McpToolRunner
import me.rerere.rikkahub.standalone.ai.tools.LocalToolRunner
import me.rerere.rikkahub.standalone.ai.tools.ToolCall
import me.rerere.rikkahub.standalone.ai.tools.ToolExecutionResult
import me.rerere.rikkahub.standalone.ai.tools.ToolJson
import me.rerere.rikkahub.standalone.ai.tools.ToolSpec

class GenerationManager(
    private val dataDir: Path,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val http = OkHttpClient.Builder().build()
    private val openai = OpenAiStreamingClient(http)
    private val claude = ClaudeStreamingClient(http)
    private val mcpTools = McpToolRunner(dataDir)
    private val localTools = LocalToolRunner(dataDir)

    private val pendingApprovals = ConcurrentHashMap<String, PendingApproval>()

    fun isGenerating(conversationId: String): Boolean = jobs.containsKey(conversationId)

    fun stop(conversationId: String) {
        jobs.remove(conversationId)?.cancel()
    }

    fun start(conversationId: String) {
        startInternal(GenerationRequest(conversationId = conversationId))
    }

    fun startRegenerate(conversationId: String, targetNodeId: String, historyNodeIndexExclusive: Int) {
        startInternal(
            GenerationRequest(
                conversationId = conversationId,
                targetNodeId = targetNodeId,
                historyNodeIndexExclusive = historyNodeIndexExclusive,
            )
        )
    }

    private fun startInternal(req: GenerationRequest) {
        if (jobs.containsKey(req.conversationId)) return
        val job = scope.launch {
            try {
                runGeneration(req)
            } finally {
                jobs.remove(req.conversationId)
            }
        }
        jobs[req.conversationId] = job
    }

    fun approveToolCall(conversationId: String, toolCallId: String, approved: Boolean, reason: String) {
        val pending = pendingApprovals[toolCallId] ?: return
        if (pending.conversationId != conversationId) return
        val nextType = if (approved) "approved" else "denied"
        updateAssistantToolPart(
            conversationId = conversationId,
            nodeId = pending.nodeId,
            toolCallId = toolCallId,
            approvalType = nextType,
            approvalReason = reason.takeIf { it.isNotBlank() },
            outputParts = null,
        )
        pending.deferred.complete(ApprovalDecision(approved = approved, reason = reason))
    }

    private suspend fun runGeneration(req: GenerationRequest) {
        val conversationId = req.conversationId
        val settingsRoot = SettingsSnapshotReader.readRootFromDataDir(dataDir)
        val settings = SettingsSnapshotReader.parse(settingsRoot)
        val assistant = settings.resolveActiveAssistant()
        val selected = SettingsSnapshotReader.selectModel(settings)

        fun fail(message: String) {
            val target = req.targetNodeId
            if (target != null) {
                updateAssistantNodeText(conversationId, target, message, finished = true)
            } else {
                insertAssistantMessage(conversationId, message)
            }
        }

        if (assistant == null) {
            fail("No assistant configured")
            return
        }
        if (selected == null) {
            fail("No model selected")
            return
        }

        val provider = selected.provider
        val model = selected.model
        val apiKey = firstKey(provider.apiKey)
        if (apiKey.isBlank()) {
            fail("Provider API key is not configured")
            return
        }

        val history = loadConversationHistory(conversationId, req.historyNodeIndexExclusive)
        val system = assistant.systemPrompt

        val enabledMcpServers = assistant.mcpServers.toSet()
        val mcpToolSpecs = mcpTools.listTools(settingsRoot, enabledMcpServers)
        val localToolSpecs = localTools.listTools(assistant.localTools)
        val toolSpecs = LinkedHashMap<String, ToolSpec>().apply {
            putAll(localToolSpecs)
            putAll(mcpToolSpecs)
        }

        val assistantNodeId = req.targetNodeId ?: insertAssistantMessage(conversationId, "")
        if (assistantNodeId == null) return

        var buffer = StringBuilder()
        var lastFlushAt = 0L

        fun flush(force: Boolean) {
            val now = System.currentTimeMillis()
            if (!force && now - lastFlushAt < 250) return
            lastFlushAt = now
            updateAssistantNodeText(conversationId, assistantNodeId, buffer.toString(), finished = false)
        }

        try {
            when (provider.type) {
                "openai" -> {
                    val path = provider.chatCompletionsPath?.takeIf { it.isNotBlank() } ?: "/chat/completions"
                    val baseUrl = provider.baseUrl.takeIf { it.isNotBlank() } ?: "https://api.openai.com/v1"

                    val toolsJson = toOpenAiToolsJson(toolSpecs.values.toList())
                    val openAiMessages = buildOpenAiMessages(system, history)
                    runOpenAiLoop(
                        conversationId = conversationId,
                        nodeId = assistantNodeId,
                        settingsRoot = settingsRoot,
                        enabledMcpServers = enabledMcpServers,
                        toolSpecs = toolSpecs,
                        localToolNames = localToolSpecs.keys,
                        baseUrl = baseUrl,
                        path = path,
                        apiKey = apiKey,
                        modelId = model.modelId,
                        messages = openAiMessages,
                        toolsJson = toolsJson,
                        temperature = assistant.temperature,
                        topP = assistant.topP,
                        maxTokens = assistant.maxTokens,
                        buffer = buffer,
                        flush = ::flush,
                    )
                }

                "claude" -> {
                    val baseUrl = provider.baseUrl.takeIf { it.isNotBlank() } ?: "https://api.anthropic.com/v1"

                    val toolsJson = toClaudeToolsJson(toolSpecs.values.toList())
                    val claudeMessages = buildClaudeMessages(history)
                    runClaudeLoop(
                        conversationId = conversationId,
                        nodeId = assistantNodeId,
                        settingsRoot = settingsRoot,
                        enabledMcpServers = enabledMcpServers,
                        toolSpecs = toolSpecs,
                        localToolNames = localToolSpecs.keys,
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        modelId = model.modelId,
                        system = system,
                        messages = claudeMessages,
                        toolsJson = toolsJson,
                        temperature = assistant.temperature,
                        topP = assistant.topP,
                        maxTokens = assistant.maxTokens,
                        buffer = buffer,
                        flush = ::flush,
                    )
                }

                else -> {
                    buffer.append("Unsupported provider type: ${provider.type}")
                }
            }
        } catch (t: Throwable) {
            val msg = t.message?.take(500) ?: "AI request failed"
            buffer.append("\n[Error] ").append(msg)
        } finally {
            pendingApprovals.entries.removeIf { it.value.conversationId == conversationId }
        }

        flush(force = true)
        updateAssistantNodeText(conversationId, assistantNodeId, buffer.toString(), finished = true)
        delay(50.milliseconds)
    }

    private suspend fun runOpenAiLoop(
        conversationId: String,
        nodeId: String,
        settingsRoot: JsonObject,
        enabledMcpServers: Set<String>,
        toolSpecs: Map<String, ToolSpec>,
        localToolNames: Set<String>,
        baseUrl: String,
        path: String,
        apiKey: String,
        modelId: String,
        messages: MutableList<JsonObject>,
        toolsJson: JsonArray,
        temperature: Double?,
        topP: Double?,
        maxTokens: Int?,
        buffer: StringBuilder,
        flush: (Boolean) -> Unit,
    ) {
        val maxSteps = 8
        for (step in 0 until maxSteps) {
            val stepStart = buffer.length
            val res = openai.streamChat(
                OpenAiStreamingClient.Params(
                    baseUrl = baseUrl,
                    chatCompletionsPath = path,
                    apiKey = apiKey,
                    remoteModelId = modelId,
                    messages = JsonArray(messages),
                    temperature = temperature,
                    topP = topP,
                    maxTokens = maxTokens,
                    tools = toolsJson,
                )
            ) { delta ->
                buffer.append(delta)
                flush(false)
            }
            val stepText = buffer.substring(stepStart)
            if (res.toolCalls.isEmpty()) {
                messages.add(openAiAssistantMessage(stepText, emptyList()))
                return
            }

            val calls = res.toolCalls.filter { it.name.isNotBlank() }
            messages.add(openAiAssistantMessage(stepText, calls))

            for (call in calls) {
                val spec = toolSpecs[call.name]
                val needsApproval = spec?.needsApproval == true
                appendAssistantToolPart(
                    conversationId = conversationId,
                    nodeId = nodeId,
                    part = ToolJson.toolPart(
                        toolCallId = call.id,
                        toolName = call.name,
                        inputJson = call.argumentsJson,
                        approvalType = if (needsApproval) "pending" else "auto",
                    )
                )

                val decision = if (needsApproval) {
                    val deferred = CompletableDeferred<ApprovalDecision>()
                    pendingApprovals[call.id] = PendingApproval(conversationId, nodeId, deferred)
                    deferred.await()
                } else {
                    ApprovalDecision(approved = true, reason = "")
                }

                val toolResult = if (!decision.approved) {
                    ToolExecutionResult(
                        outputParts = listOf(ToolJson.textPart("Denied: ${decision.reason}".trim())),
                        modelText = "Denied: ${decision.reason}".trim(),
                    )
                } else {
                    runCatching {
                        val argsObj = parseJsonObjectOrEmpty(call.argumentsJson)
                        if (localToolNames.contains(call.name)) {
                            localTools.callTool(call.name, argsObj)
                        } else {
                            mcpTools.callTool(settingsRoot, enabledMcpServers, call.name, argsObj)
                        }
                    }.getOrElse { t ->
                        toolExceptionResult(t)
                    }
                }

                updateAssistantToolPart(
                    conversationId = conversationId,
                    nodeId = nodeId,
                    toolCallId = call.id,
                    approvalType = when {
                        needsApproval && decision.approved -> "approved"
                        needsApproval && !decision.approved -> "denied"
                        else -> "auto"
                    },
                    approvalReason = decision.reason.takeIf { it.isNotBlank() },
                    outputParts = toolResult.outputParts,
                )

                messages.add(
                    JsonObject(
                        mapOf(
                            "role" to JsonPrimitive("tool"),
                            "tool_call_id" to JsonPrimitive(call.id),
                            "content" to JsonPrimitive(toolResult.modelText),
                        )
                    )
                )
            }
        }

        buffer.append("\n[Error] Tool loop exceeded step limit")
    }

    private suspend fun runClaudeLoop(
        conversationId: String,
        nodeId: String,
        settingsRoot: JsonObject,
        enabledMcpServers: Set<String>,
        toolSpecs: Map<String, ToolSpec>,
        localToolNames: Set<String>,
        baseUrl: String,
        apiKey: String,
        modelId: String,
        system: String,
        messages: MutableList<JsonObject>,
        toolsJson: JsonArray,
        temperature: Double?,
        topP: Double?,
        maxTokens: Int?,
        buffer: StringBuilder,
        flush: (Boolean) -> Unit,
    ) {
        val maxSteps = 8
        for (step in 0 until maxSteps) {
            val stepStart = buffer.length
            val res = claude.streamChat(
                ClaudeStreamingClient.Params(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    remoteModelId = modelId,
                    system = system,
                    messages = JsonArray(messages),
                    temperature = temperature,
                    topP = topP,
                    maxTokens = maxTokens,
                    tools = toolsJson,
                )
            ) { delta ->
                buffer.append(delta)
                flush(false)
            }

            val stepText = buffer.substring(stepStart)
            if (res.toolCalls.isEmpty()) {
                messages.add(claudeAssistantMessage(text = stepText, toolCalls = emptyList()))
                return
            }

            val calls = res.toolCalls.filter { it.name.isNotBlank() }
            messages.add(claudeAssistantMessage(text = stepText, toolCalls = calls))

            val toolResultBlocks = mutableListOf<JsonObject>()
            for (call in calls) {
                val spec = toolSpecs[call.name]
                val needsApproval = spec?.needsApproval == true
                appendAssistantToolPart(
                    conversationId = conversationId,
                    nodeId = nodeId,
                    part = ToolJson.toolPart(
                        toolCallId = call.id,
                        toolName = call.name,
                        inputJson = call.argumentsJson,
                        approvalType = if (needsApproval) "pending" else "auto",
                    )
                )

                val decision = if (needsApproval) {
                    val deferred = CompletableDeferred<ApprovalDecision>()
                    pendingApprovals[call.id] = PendingApproval(conversationId, nodeId, deferred)
                    deferred.await()
                } else {
                    ApprovalDecision(approved = true, reason = "")
                }

                val toolResult = if (!decision.approved) {
                    ToolExecutionResult(
                        outputParts = listOf(ToolJson.textPart("Denied: ${decision.reason}".trim())),
                        modelText = "Denied: ${decision.reason}".trim(),
                    )
                } else {
                    runCatching {
                        val argsObj = parseJsonObjectOrEmpty(call.argumentsJson)
                        if (localToolNames.contains(call.name)) {
                            localTools.callTool(call.name, argsObj)
                        } else {
                            mcpTools.callTool(settingsRoot, enabledMcpServers, call.name, argsObj)
                        }
                    }.getOrElse { t ->
                        toolExceptionResult(t)
                    }
                }

                updateAssistantToolPart(
                    conversationId = conversationId,
                    nodeId = nodeId,
                    toolCallId = call.id,
                    approvalType = when {
                        needsApproval && decision.approved -> "approved"
                        needsApproval && !decision.approved -> "denied"
                        else -> "auto"
                    },
                    approvalReason = decision.reason.takeIf { it.isNotBlank() },
                    outputParts = toolResult.outputParts,
                )

                toolResultBlocks.add(
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("tool_result"),
                            "tool_use_id" to JsonPrimitive(call.id),
                            "content" to JsonArray(
                                listOf(
                                    JsonObject(
                                        mapOf(
                                            "type" to JsonPrimitive("text"),
                                            "text" to JsonPrimitive(toolResult.modelText),
                                        )
                                    )
                                )
                            ),
                        )
                    )
                )
            }

            messages.add(
                JsonObject(
                    mapOf(
                        "role" to JsonPrimitive("user"),
                        "content" to JsonArray(toolResultBlocks),
                    )
                )
            )
        }

        buffer.append("\n[Error] Tool loop exceeded step limit")
    }

    private data class ApprovalDecision(val approved: Boolean, val reason: String)

    private data class PendingApproval(
        val conversationId: String,
        val nodeId: String,
        val deferred: CompletableDeferred<ApprovalDecision>,
    )

    private data class GenerationRequest(
        val conversationId: String,
        val targetNodeId: String? = null,
        val historyNodeIndexExclusive: Int? = null,
    )

    private fun buildOpenAiMessages(system: String, history: List<Pair<String, String>>): MutableList<JsonObject> {
        val out = mutableListOf<JsonObject>()
        if (system.isNotBlank()) {
            out.add(JsonObject(mapOf("role" to JsonPrimitive("system"), "content" to JsonPrimitive(system))))
        }
        for ((role, content) in history) {
            if (content.isBlank()) continue
            out.add(JsonObject(mapOf("role" to JsonPrimitive(role), "content" to JsonPrimitive(content))))
        }
        return out
    }

    private fun buildClaudeMessages(history: List<Pair<String, String>>): MutableList<JsonObject> {
        val out = mutableListOf<JsonObject>()
        for ((role, content) in history) {
            if (role == "system" || content.isBlank()) continue
            val r = if (role == "assistant") "assistant" else "user"
            out.add(
                JsonObject(
                    mapOf(
                        "role" to JsonPrimitive(r),
                        "content" to JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("text"),
                                        "text" to JsonPrimitive(content),
                                    )
                                )
                            )
                        ),
                    )
                )
            )
        }
        return out
    }

    private fun toOpenAiToolsJson(tools: List<ToolSpec>): JsonArray {
        return JsonArray(
            tools.map { tool ->
                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("function"),
                        "function" to JsonObject(
                            mapOf(
                                "name" to JsonPrimitive(tool.name),
                                "description" to JsonPrimitive(tool.description ?: ""),
                                "parameters" to tool.inputSchema,
                            )
                        )
                    )
                )
            }
        )
    }

    private fun toClaudeToolsJson(tools: List<ToolSpec>): JsonArray {
        return JsonArray(
            tools.map { tool ->
                JsonObject(
                    mapOf(
                        "name" to JsonPrimitive(tool.name),
                        "description" to JsonPrimitive(tool.description ?: ""),
                        "input_schema" to tool.inputSchema,
                    )
                )
            }
        )
    }

    private fun openAiAssistantMessage(text: String, toolCalls: List<ToolCall>): JsonObject {
        val base = linkedMapOf<String, JsonElement>(
            "role" to JsonPrimitive("assistant"),
            "content" to JsonPrimitive(text),
        )
        if (toolCalls.isNotEmpty()) {
            base["tool_calls"] = JsonArray(
                toolCalls.map { tc ->
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive(tc.id),
                            "type" to JsonPrimitive("function"),
                            "function" to JsonObject(
                                mapOf(
                                    "name" to JsonPrimitive(tc.name),
                                    "arguments" to JsonPrimitive(tc.argumentsJson),
                                )
                            ),
                        )
                    )
                }
            )
        }
        return JsonObject(base)
    }

    private fun claudeAssistantMessage(text: String, toolCalls: List<ToolCall>): JsonObject {
        val blocks = mutableListOf<JsonObject>()
        if (text.isNotBlank()) {
            blocks.add(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text))))
        }
        for (tc in toolCalls) {
            val input = runCatching { json.parseToJsonElement(tc.argumentsJson) }
                .getOrElse { JsonObject(emptyMap()) }
            blocks.add(
                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("tool_use"),
                        "id" to JsonPrimitive(tc.id),
                        "name" to JsonPrimitive(tc.name),
                        "input" to input,
                    )
                )
            )
        }
        return JsonObject(
            mapOf(
                "role" to JsonPrimitive("assistant"),
                "content" to JsonArray(blocks),
            )
        )
    }

    private fun parseJsonObjectOrEmpty(raw: String): JsonObject {
        val el = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        return (el as? JsonObject) ?: JsonObject(emptyMap())
    }

    private fun toolExceptionResult(t: Throwable): ToolExecutionResult {
        val msg = (t.message ?: t::class.java.simpleName).take(300)
        val text = "Tool error: $msg"
        return ToolExecutionResult(outputParts = listOf(ToolJson.textPart(text)), modelText = text)
    }

    private fun appendAssistantToolPart(conversationId: String, nodeId: String, part: JsonObject) {
        val dbPath = dataDir.resolve("db").resolve("rikka_hub.db")
        val now = System.currentTimeMillis()
        withSqliteConnection(dbPath) { conn ->
            conn.autoCommit = false
            try {
                val row = conn.prepareStatement("SELECT messages, select_index FROM message_node WHERE id = ?").use { ps ->
                    ps.setString(1, nodeId)
                    ps.executeQuery().use { rs ->
                        if (!rs.next()) return@use null
                        rs.getString("messages") to rs.getInt("select_index")
                    }
                } ?: return@withSqliteConnection
                val messagesJson = row.first
                val selectIndex = row.second

                val arr = runCatching { json.parseToJsonElement(messagesJson) as JsonArray }.getOrNull() ?: return@withSqliteConnection
                val selected = selectIndex.takeIf { it >= 0 && it < arr.size } ?: 0
                val msg = (arr.getOrNull(selected) as? JsonObject) ?: return@withSqliteConnection
                val parts = msg["parts"] as? JsonArray ?: JsonArray(emptyList())
                val nextParts = JsonArray(parts + part)
                val nextMsg = JsonObject(msg.toMutableMap().apply { put("parts", nextParts) })
                val nextArr = JsonArray(arr.mapIndexed { idx, el -> if (idx == selected) nextMsg else el })
                conn.prepareStatement("UPDATE message_node SET messages = ? WHERE id = ?").use { ups ->
                    ups.setString(1, json.encodeToString(nextArr))
                    ups.setString(2, nodeId)
                    ups.executeUpdate()
                }
                updateConversationTimestamp(conn, conversationId, now)
                conn.commit()
            } catch (_: Throwable) {
                runCatching { conn.rollback() }
            } finally {
                conn.autoCommit = true
            }
        }
    }

    private fun updateAssistantToolPart(
        conversationId: String,
        nodeId: String,
        toolCallId: String,
        approvalType: String,
        approvalReason: String?,
        outputParts: List<JsonObject>?,
    ) {
        val dbPath = dataDir.resolve("db").resolve("rikka_hub.db")
        val now = System.currentTimeMillis()
        withSqliteConnection(dbPath) { conn ->
            conn.autoCommit = false
            try {
                val row = conn.prepareStatement("SELECT messages, select_index FROM message_node WHERE id = ?").use { ps ->
                    ps.setString(1, nodeId)
                    ps.executeQuery().use { rs ->
                        if (!rs.next()) return@use null
                        rs.getString("messages") to rs.getInt("select_index")
                    }
                } ?: return@withSqliteConnection
                val messagesJson = row.first
                val selectIndex = row.second

                val arr = runCatching { json.parseToJsonElement(messagesJson) as JsonArray }.getOrNull() ?: return@withSqliteConnection
                val selected = selectIndex.takeIf { it >= 0 && it < arr.size } ?: 0
                val msg = (arr.getOrNull(selected) as? JsonObject) ?: return@withSqliteConnection
                val parts = msg["parts"] as? JsonArray ?: JsonArray(emptyList())
                val nextParts = JsonArray(
                    parts.map { el ->
                        val obj = el as? JsonObject ?: return@map el
                        val type = obj["type"]?.jsonPrimitive?.contentOrNull
                        if (type != "tool") return@map el
                        val id = obj["toolCallId"]?.jsonPrimitive?.contentOrNull
                        if (id != toolCallId) return@map el
                        val next = obj.toMutableMap()
                        val approval = linkedMapOf<String, JsonElement>("type" to JsonPrimitive(approvalType))
                        if (!approvalReason.isNullOrBlank()) approval["reason"] = JsonPrimitive(approvalReason)
                        next["approvalState"] = JsonObject(approval)
                        if (outputParts != null) {
                            next["output"] = JsonArray(outputParts)
                        }
                        JsonObject(next)
                    }
                )
                val nextMsg = JsonObject(msg.toMutableMap().apply { put("parts", nextParts) })
                val nextArr = JsonArray(arr.mapIndexed { idx, el -> if (idx == selected) nextMsg else el })
                conn.prepareStatement("UPDATE message_node SET messages = ? WHERE id = ?").use { ups ->
                    ups.setString(1, json.encodeToString(nextArr))
                    ups.setString(2, nodeId)
                    ups.executeUpdate()
                }
                updateConversationTimestamp(conn, conversationId, now)
                conn.commit()
            } catch (_: Throwable) {
                runCatching { conn.rollback() }
            } finally {
                conn.autoCommit = true
            }
        }
    }

    private fun buildMessages(system: String, history: List<Pair<String, String>>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        if (system.isNotBlank()) {
            out.add("system" to system)
        }
        for ((role, content) in history) {
            if (content.isBlank()) continue
            out.add(role to content)
        }
        return out
    }

    private fun firstKey(raw: String): String {
        return raw
            .split("\n", ",", ";")
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
    }

    private fun loadConversationHistory(conversationId: String): List<Pair<String, String>> {
        return loadConversationHistory(conversationId, null)
    }

    private fun loadConversationHistory(conversationId: String, nodeIndexExclusive: Int?): List<Pair<String, String>> {
        val dbPath = dataDir.resolve("db").resolve("rikka_hub.db")
        return withSqliteConnection(dbPath) { conn ->
            val sql = if (nodeIndexExclusive == null) {
                """
                SELECT node_index, messages, select_index
                FROM message_node
                WHERE conversation_id = ?
                ORDER BY node_index ASC
                """.trimIndent()
            } else {
                """
                SELECT node_index, messages, select_index
                FROM message_node
                WHERE conversation_id = ? AND node_index < ?
                ORDER BY node_index ASC
                """.trimIndent()
            }

            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, conversationId)
                if (nodeIndexExclusive != null) {
                    ps.setInt(2, nodeIndexExclusive)
                }
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<Pair<String, String>>()
                    while (rs.next()) {
                        val selectIndex = rs.getInt("select_index")
                        val messagesJson = rs.getString("messages")
                        val arr = runCatching { json.parseToJsonElement(messagesJson) as JsonArray }
                            .getOrElse { continue }
                        val selected = arr.getOrNull(selectIndex) ?: arr.getOrNull(0) ?: continue
                        val obj = selected as? JsonObject ?: continue
                        val roleRaw = obj["role"]?.jsonPrimitive?.contentOrNull ?: continue
                        val role = when (roleRaw.uppercase()) {
                            "USER" -> "user"
                            "ASSISTANT" -> "assistant"
                            "SYSTEM" -> "system"
                            else -> "user"
                        }
                        val parts = obj["parts"] as? JsonArray ?: continue
                        val text = extractText(parts)
                        if (text.isNotBlank()) out.add(role to text)
                    }
                    out
                }
            }
        }
    }

    private fun extractText(parts: JsonArray): String {
        val sb = StringBuilder()
        for (el in parts) {
            val obj = el as? JsonObject ?: continue
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: continue
            if (type == "text") {
                val t = obj["text"]?.jsonPrimitive?.contentOrNull
                if (!t.isNullOrBlank()) {
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(t)
                }
            }
        }
        return sb.toString()
    }

    private fun insertAssistantMessage(conversationId: String, text: String): String? {
        val dbPath = dataDir.resolve("db").resolve("rikka_hub.db")
        val now = System.currentTimeMillis()
        val messageId = UUID.randomUUID().toString()
        val message = JsonObject(
            mapOf(
                "id" to JsonPrimitive(messageId),
                "role" to JsonPrimitive("ASSISTANT"),
                "parts" to JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text))))),
                "annotations" to JsonArray(emptyList()),
                "createdAt" to JsonPrimitive(LocalDateTime.now().toString()),
                "finishedAt" to JsonNull,
                "modelId" to JsonNull,
                "usage" to JsonNull,
                "translation" to JsonNull,
            )
        )

        return withSqliteConnection(dbPath) { conn ->
            conn.autoCommit = false
            try {
                ensureConversationExists(conn, conversationId, now)
                val nodeIndex = nextNodeIndex(conn, conversationId)
                val nodeId = insertNode(conn, conversationId, nodeIndex, JsonArray(listOf(message)))
                appendNodeId(conn, conversationId, nodeId)
                updateConversationTimestamp(conn, conversationId, now)
                conn.commit()
                nodeId
            } catch (t: Throwable) {
                runCatching { conn.rollback() }
                null
            } finally {
                conn.autoCommit = true
            }
        }
    }

    private fun updateAssistantNodeText(conversationId: String, nodeId: String, text: String, finished: Boolean) {
        val dbPath = dataDir.resolve("db").resolve("rikka_hub.db")
        val now = System.currentTimeMillis()
        withSqliteConnection(dbPath) { conn ->
            conn.autoCommit = false
            try {
                val row = conn.prepareStatement("SELECT messages, select_index FROM message_node WHERE id = ?").use { ps ->
                    ps.setString(1, nodeId)
                    ps.executeQuery().use { rs ->
                        if (!rs.next()) return@use null
                        rs.getString("messages") to rs.getInt("select_index")
                    }
                } ?: return@withSqliteConnection
                val messagesJson = row.first
                val selectIndex = row.second

                val arr = runCatching { json.parseToJsonElement(messagesJson) as JsonArray }.getOrNull() ?: return@withSqliteConnection
                val selected = selectIndex.takeIf { it >= 0 && it < arr.size } ?: 0
                val msg = (arr.getOrNull(selected) as? JsonObject) ?: return@withSqliteConnection
                val parts = msg["parts"] as? JsonArray ?: JsonArray(emptyList())
                val nextParts = JsonArray(
                    if (parts.isNotEmpty()) {
                        parts.mapIndexed { idx, el ->
                            if (idx != 0) el
                            else {
                                val o = el as? JsonObject
                                if (o != null && o["type"]?.jsonPrimitive?.contentOrNull == "text") {
                                    JsonObject(o.toMutableMap().apply { put("text", JsonPrimitive(text)) })
                                } else {
                                    JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text)))
                                }
                            }
                        }
                    } else {
                        listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text))))
                    }
                )
                val nextMsg = JsonObject(msg.toMutableMap().apply {
                    put("parts", nextParts)
                    if (finished) {
                        put("finishedAt", JsonPrimitive(LocalDateTime.now().toString()))
                    }
                })
                val nextArr = JsonArray(arr.mapIndexed { idx, el -> if (idx == selected) nextMsg else el })

                conn.prepareStatement("UPDATE message_node SET messages = ? WHERE id = ?").use { ups ->
                    ups.setString(1, json.encodeToString(nextArr))
                    ups.setString(2, nodeId)
                    ups.executeUpdate()
                }

                updateConversationTimestamp(conn, conversationId, now)
                conn.commit()
            } catch (_: Throwable) {
                runCatching { conn.rollback() }
            } finally {
                conn.autoCommit = true
            }
        }
    }

    private fun ensureConversationExists(conn: java.sql.Connection, id: String, now: Long) {
        conn.prepareStatement("SELECT 1 FROM conversationentity WHERE id = ? LIMIT 1").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                if (rs.next()) return
            }
        }
        conn.prepareStatement(
            """
            INSERT INTO conversationentity (
                id, assistant_id, title, nodes, create_at, update_at, truncate_index, suggestions, is_pinned
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, "")
            ps.setString(3, "New Chat")
            ps.setString(4, "[]")
            ps.setLong(5, now)
            ps.setLong(6, now)
            ps.setInt(7, -1)
            ps.setString(8, "[]")
            ps.setInt(9, 0)
            ps.executeUpdate()
        }
    }

    private fun nextNodeIndex(conn: java.sql.Connection, conversationId: String): Int {
        conn.prepareStatement("SELECT COALESCE(MAX(node_index), -1) AS m FROM message_node WHERE conversation_id = ?").use { ps ->
            ps.setString(1, conversationId)
            ps.executeQuery().use { rs ->
                rs.next()
                return rs.getInt("m") + 1
            }
        }
    }

    private fun insertNode(conn: java.sql.Connection, conversationId: String, nodeIndex: Int, messages: JsonArray): String {
        val nodeId = UUID.randomUUID().toString()
        conn.prepareStatement(
            """
            INSERT INTO message_node (id, conversation_id, node_index, messages, select_index)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, nodeId)
            ps.setString(2, conversationId)
            ps.setInt(3, nodeIndex)
            ps.setString(4, json.encodeToString(messages))
            ps.setInt(5, 0)
            ps.executeUpdate()
        }
        return nodeId
    }

    private fun appendNodeId(conn: java.sql.Connection, conversationId: String, nodeId: String) {
        val nodesJson = conn.prepareStatement("SELECT nodes FROM conversationentity WHERE id = ?").use { ps ->
            ps.setString(1, conversationId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return
                rs.getString("nodes")
            }
        } ?: return

        val arr = runCatching { json.parseToJsonElement(nodesJson) as JsonArray }.getOrElse { JsonArray(emptyList()) }
        val next = JsonArray(arr + JsonPrimitive(nodeId))
        conn.prepareStatement("UPDATE conversationentity SET nodes = ? WHERE id = ?").use { ps ->
            ps.setString(1, json.encodeToString(next))
            ps.setString(2, conversationId)
            ps.executeUpdate()
        }
    }

    private fun updateConversationTimestamp(conn: java.sql.Connection, conversationId: String, now: Long) {
        conn.prepareStatement("UPDATE conversationentity SET update_at = ? WHERE id = ?").use { ps ->
            ps.setLong(1, now)
            ps.setString(2, conversationId)
            ps.executeUpdate()
        }
    }
}

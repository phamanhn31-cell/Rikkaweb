package me.rerere.rikkahub.standalone.ai.tools

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import me.rerere.rikkahub.standalone.mcp.transport.SseClientTransport
import me.rerere.rikkahub.standalone.mcp.transport.StreamableHttpClientTransport
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

class McpToolRunner(private val dataDir: Path) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(this@McpToolRunner.json) }
        install(SSE)
    }

    private val clients = ConcurrentHashMap<String, Client>()
    private val clientLocks = ConcurrentHashMap<String, Mutex>()

    fun listTools(root: JsonObject, enabledServerIds: Set<String>): Map<String, ToolSpec> {
        val servers = parseServers(root)
            .filter { it.enable && enabledServerIds.contains(it.id) }

        val tools = LinkedHashMap<String, ToolSpec>()
        for (server in servers) {
            for (tool in server.tools) {
                if (!tool.enable) continue
                if (tools.containsKey(tool.name)) continue
                tools[tool.name] = ToolSpec(
                    name = tool.name,
                    description = tool.description,
                    inputSchema = tool.inputSchema,
                    needsApproval = tool.needsApproval,
                )
            }
        }
        return tools
    }

    suspend fun callTool(
        root: JsonObject,
        enabledServerIds: Set<String>,
        toolName: String,
        args: JsonObject,
    ): ToolExecutionResult {
        val serverAndTool = findTool(root, enabledServerIds, toolName)
            ?: return ToolExecutionResult(
                outputParts = listOf(ToolJson.textPart("MCP tool not found: $toolName")),
                modelText = "MCP tool not found: $toolName",
            )

        val (server, tool) = serverAndTool
        val client = ensureClient(server)

        val result = client.callTool(
            CallToolRequest(
                CallToolRequestParams(
                    name = tool.name,
                    arguments = args,
                )
            )
        )

        val parts = mutableListOf<JsonObject>()
        val modelTextPieces = mutableListOf<String>()

        result.content?.forEach { content ->
            when (content) {
                is TextContent -> {
                    parts.add(ToolJson.textPart(content.text))
                    modelTextPieces.add(content.text)
                }

                is ImageContent -> {
                    val url = saveMcpImage(content)
                    parts.add(ToolJson.imagePart(url))
                    modelTextPieces.add(url)
                }

                else -> {
                    val encoded = content.toString()
                    parts.add(ToolJson.textPart(encoded))
                    modelTextPieces.add(encoded)
                }
            }
        }

        if (parts.isEmpty()) {
            val msg = "MCP tool returned no content: $toolName"
            parts.add(ToolJson.textPart(msg))
            modelTextPieces.add(msg)
        }

        return ToolExecutionResult(
            outputParts = parts,
            modelText = modelTextPieces.joinToString("\n"),
        )
    }

    private suspend fun ensureClient(server: McpServer): Client {
        val lock = clientLocks.computeIfAbsent(server.id) { Mutex() }
        return lock.withLock {
            val cached = clients[server.id]
            if (cached != null && cached.transport != null) return@withLock cached

            val client = cached ?: Client(
                clientInfo = Implementation(
                    name = server.name.ifBlank { server.id },
                    version = "1.0",
                )
            )

            val transport = when (server.type) {
                "sse" -> SseClientTransport(
                    client = httpClient,
                    urlString = server.url,
                    requestBuilder = { server.headers.forEach { headers.append(it.first, it.second) } },
                )

                "streamable_http" -> StreamableHttpClientTransport(
                    client = httpClient,
                    url = server.url,
                    requestBuilder = { server.headers.forEach { headers.append(it.first, it.second) } },
                )

                else -> error("Unsupported MCP transport type: ${server.type}")
            }

            client.connect(transport)
            clients[server.id] = client
            return@withLock client
        }
    }

    private fun saveMcpImage(content: ImageContent): String {
        val uploadDir = dataDir.resolve("upload").resolve("mcp")
        Files.createDirectories(uploadDir)

        val ext = when (content.mimeType.lowercase()) {
            "image/png" -> ".png"
            "image/jpeg" -> ".jpg"
            "image/jpg" -> ".jpg"
            "image/gif" -> ".gif"
            "image/webp" -> ".webp"
            else -> ".bin"
        }
        val name = UUID.randomUUID().toString() + ext
        val bytes = java.util.Base64.getDecoder().decode(content.data)
        Files.write(uploadDir.resolve(name), bytes)
        return "/api/files/path/upload/mcp/$name"
    }

    private fun findTool(
        root: JsonObject,
        enabledServerIds: Set<String>,
        toolName: String,
    ): Pair<McpServer, McpTool>? {
        val servers = parseServers(root)
            .filter { it.enable && enabledServerIds.contains(it.id) }
        for (server in servers) {
            val tool = server.tools.firstOrNull { it.enable && it.name == toolName } ?: continue
            return server to tool
        }
        return null
    }

    private data class McpTool(
        val enable: Boolean,
        val name: String,
        val description: String?,
        val inputSchema: JsonObject,
        val needsApproval: Boolean,
    )

    private data class McpServer(
        val id: String,
        val type: String,
        val url: String,
        val enable: Boolean,
        val name: String,
        val headers: List<Pair<String, String>>,
        val tools: List<McpTool>,
    )

    private fun parseServers(root: JsonObject): List<McpServer> {
        val servers = root["mcpServers"] as? JsonArray ?: return emptyList()
        return servers.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val type = obj["type"].stringOrNull() ?: return@mapNotNull null
            val id = obj["id"].stringOrNull() ?: return@mapNotNull null
            val url = obj["url"].stringOrNull() ?: return@mapNotNull null
            val common = obj["commonOptions"] as? JsonObject ?: JsonObject(emptyMap())
            val enable = (common["enable"] as? JsonPrimitive)?.booleanOrNull ?: true
            val name = common["name"].stringOrNull() ?: ""
            val headers = parseHeaders(common["headers"])
            val tools = parseTools(common["tools"])
            McpServer(
                id = id,
                type = type,
                url = url,
                enable = enable,
                name = name,
                headers = headers,
                tools = tools,
            )
        }
    }

    private fun parseTools(el: JsonElement?): List<McpTool> {
        val arr = el as? JsonArray ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val enable = (obj["enable"] as? JsonPrimitive)?.booleanOrNull ?: true
            val name = obj["name"].stringOrNull() ?: return@mapNotNull null
            val description = obj["description"].stringOrNull()
            val needsApproval = (obj["needsApproval"] as? JsonPrimitive)?.booleanOrNull ?: false
            val schema = inputSchemaToJsonSchema(obj["inputSchema"])
            McpTool(enable = enable, name = name, description = description, inputSchema = schema, needsApproval = needsApproval)
        }
    }

    private fun inputSchemaToJsonSchema(el: JsonElement?): JsonObject {
        val obj = el as? JsonObject
        val properties = (obj?.get("properties") as? JsonObject) ?: JsonObject(emptyMap())
        val required = obj?.get("required")
        val next = LinkedHashMap<String, JsonElement>()
        next["type"] = JsonPrimitive("object")
        next["properties"] = properties
        if (required is JsonArray) {
            next["required"] = required
        }
        return JsonObject(next)
    }

    private fun parseHeaders(el: JsonElement?): List<Pair<String, String>> {
        val arr = el as? JsonArray ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (item in arr) {
            when (item) {
                is JsonArray -> {
                    if (item.size >= 2) {
                        val k = (item[0] as? JsonPrimitive)?.contentOrNull
                        val v = (item[1] as? JsonPrimitive)?.contentOrNull
                        if (!k.isNullOrBlank() && v != null) out.add(k to v)
                    }
                }

                is JsonObject -> {
                    val k = item["first"].stringOrNull() ?: item["name"].stringOrNull()
                    val v = item["second"].stringOrNull() ?: item["value"].stringOrNull()
                    if (!k.isNullOrBlank() && v != null) out.add(k to v)
                }

                else -> Unit
            }
        }
        return out
    }
}

private fun JsonElement?.stringOrNull(): String? {
    return (this as? JsonPrimitive)?.contentOrNull
}

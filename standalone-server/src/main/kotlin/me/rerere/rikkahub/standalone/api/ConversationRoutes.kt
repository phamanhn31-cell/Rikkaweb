package me.rerere.rikkahub.standalone.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDateTime
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.standalone.ai.GenerationManager
import me.rerere.rikkahub.standalone.db.withSqliteConnection

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

@Serializable
private data class SendMessageRequest(val parts: List<JsonElement>)

@Serializable
private data class EditMessageRequest(val parts: List<JsonElement>)

@Serializable
private data class RegenerateRequest(val messageId: String)

@Serializable
private data class ToolApprovalRequest(val toolCallId: String, val approved: Boolean, val reason: String = "")

@Serializable
private data class ForkConversationRequest(val messageId: String)

@Serializable
private data class ForkConversationResponse(val conversationId: String)

@Serializable
private data class SelectMessageNodeRequest(val selectIndex: Int)

@Serializable
private data class MoveConversationRequest(val assistantId: String)

@Serializable
private data class UpdateConversationTitleRequest(val title: String)

fun Route.conversationRoutes(dataDir: Path, generationManager: GenerationManager) {
    route("/conversations") {
        get("/search") {
            val q = call.request.queryParameters["query"]?.trim().orEmpty()
            if (q.isEmpty()) {
                call.respond(emptyList<MessageSearchResultDto>())
                return@get
            }

            val dbPath = dataDir.resolve("db").resolve("rikka_hub.db")
            val results = withSqliteConnection(dbPath) { conn ->
                searchMessages(conn, q, limit = 50)
            }
            call.respond(results)
        }

        get("/paged") {
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 30
            val query = call.request.queryParameters["query"]?.trim()?.takeIf { it.isNotEmpty() }

            require(offset >= 0) { "offset must be >= 0" }
            require(limit in 1..100) { "limit must be between 1 and 100" }

            val assistantIdFilter = readAssistantIdFromSettingsOrNull(dataDir)
            val dbPath = dataDir.resolve("db").resolve("rikka_hub.db")
            val rows = withSqliteConnection(dbPath) { conn ->
                queryConversationPage(conn, offset = offset, limit = limit + 1, query = query, assistantId = assistantIdFilter)
            }

            val hasMore = rows.size > limit
            val items = (if (hasMore) rows.take(limit) else rows)
                .map { it.copy(isGenerating = generationManager.isGenerating(it.id)) }
            val nextOffset = if (hasMore) offset + limit else null

            call.respond(PagedResult(items = items, nextOffset = nextOffset, hasMore = hasMore))
        }

        get("/{id}") {
            val id = call.parameters["id"] ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing id", 400))
                return@get
            }

            val dbPath = dataDir.resolve("db").resolve("rikka_hub.db")
            val conversation = withSqliteConnection(dbPath) { conn ->
                val base = queryConversationById(conn, id) ?: return@withSqliteConnection null
                val nodes = queryNodesOfConversation(conn, id)
                base.copy(messages = nodes)
            }

            if (conversation == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("conversation not found", 404))
                return@get
            }

            call.respond(conversation.copy(isGenerating = generationManager.isGenerating(id)))
        }

        post("/{id}/messages") {
            val id = call.parameters["id"].orEmpty()
            if (id.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing id", 400))
                return@post
            }
            val req = call.receive<SendMessageRequest>()

            val dbPath = dataDir.resolve("db").resolve("rikka_hub.db")
            val assistantId = readAssistantIdFromSettingsOrNull(dataDir) ?: ""
            val now = System.currentTimeMillis()
            val message = JsonObject(
                mapOf(
                    "id" to JsonPrimitive(UUID.randomUUID().toString()),
                    "role" to JsonPrimitive("USER"),
                    "parts" to JsonArray(req.parts),
                    "annotations" to JsonArray(emptyList()),
                    "createdAt" to JsonPrimitive(LocalDateTime.now().toString()),
                    "finishedAt" to JsonNull,
                    "modelId" to JsonNull,
                    "usage" to JsonNull,
                    "translation" to JsonNull,
                )
            )

            withSqliteConnection(dbPath) { conn ->
                conn.autoCommit = false
                try {
                    ensureConversationExists(conn, id, assistantId, now)
                    val nodeIndex = nextNodeIndex(conn, id)
                    val nodeId = insertNewNode(conn, id, nodeIndex, JsonArray(listOf(message)), selectIndex = 0)
                    appendNodeId(conn, id, nodeId)
                    updateConversationTimestamp(conn, id, now)
                    conn.commit()
                } catch (t: Throwable) {
                    runCatching { conn.rollback() }
                    throw t
                } finally {
                    conn.autoCommit = true
                }
            }

            generationManager.start(id)

            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }

        post("/{id}/messages/{messageId}/edit") {
            val conversationId = call.parameters["id"].orEmpty()
            val messageId = call.parameters["messageId"].orEmpty()
            if (conversationId.isBlank() || messageId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing id", 400))
                return@post
            }
            val req = call.receive<EditMessageRequest>()
            val dbPath = dataDir.resolve("db").resolve("rikka_hub.db")
            val now = System.currentTimeMillis()
            withSqliteConnection(dbPath) { conn ->
                conn.autoCommit = false
                try {
                    editMessageParts(conn, conversationId, messageId, req.parts)
                    updateConversationTimestamp(conn, conversationId, now)
                    conn.commit()
                } catch (t: Throwable) {
                    runCatching { conn.rollback() }
                    throw t
                } finally {
                    conn.autoCommit = true
                }
            }
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }

        delete("/{id}/messages/{messageId}") {
            val conversationId = call.parameters["id"].orEmpty()
            val messageId = call.parameters["messageId"].orEmpty()
            if (conversationId.isBlank() || messageId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing id", 400))
                return@delete
            }
            generationManager.stop(conversationId)
            val dbPath = dataDir.resolve("db").resolve("rikka_hub.db")
            val now = System.currentTimeMillis()
            withSqliteConnection(dbPath) { conn ->
                conn.autoCommit = false
                try {
                    deleteMessage(conn, conversationId, messageId)
                    updateConversationTimestamp(conn, conversationId, now)
                    conn.commit()
                } catch (t: Throwable) {
                    runCatching { conn.rollback() }
                    throw t
                } finally {
                    conn.autoCommit = true
                }
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
        }

        delete("/{id}") {
            val id = call.parameters["id"].orEmpty()
            if (id.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing id", 400))
                return@delete
            }
            generationManager.stop(id)
            val dbPath = dataDir.resolve("db").resolve("rikka_hub.db")
            val deleted = withSqliteConnection(dbPath) { conn ->
                conn.autoCommit = false
                try {
                    val ok = deleteConversation(conn, id)
                    conn.commit()
                    ok
                } catch (t: Throwable) {
                    runCatching { conn.rollback() }
                    throw t
                } finally {
                    conn.autoCommit = true
                }
            }

            if (!deleted) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("conversation not found", 404))
                return@delete
            }
            call.respond(HttpStatusCode.NoContent, "")
        }

        post("/{id}/pin") {
            val id = call.parameters["id"].orEmpty()
            if (id.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing id", 400))
                return@post
            }
            val dbPath = dataDir.resolve("db").resolve("rikka_hub.db")
            val now = System.currentTimeMillis()
            val updated = withSqliteConnection(dbPath) { conn ->
                conn.autoCommit = false
                try {
                    val ok = togglePinned(conn, id)
                    if (ok) updateConversationTimestamp(conn, id, now)
                    conn.commit()
                    ok
                } catch (t: Throwable) {
                    runCatching { conn.rollback() }
                    throw t
                } finally {
                    conn.autoCommit = true
                }
            }
            if (!updated) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("conversation not found", 404))
                return@post
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
        }

        post("/{id}/regenerate-title") {
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }

        post("/{id}/title") {
            val id = call.parameters["id"].orEmpty()
            if (id.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing id", 400))
                return@post
            }
            val req = call.receive<UpdateConversationTitleRequest>()
            val title = req.title.trim()
            if (title.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Title must not be blank", 400))
                return@post
            }
            val dbPath = dataDir.resolve("db").resolve("rikka_hub.db")
            val now = System.currentTimeMillis()
            val ok = withSqliteConnection(dbPath) { conn ->
                conn.autoCommit = false
                try {
                    val updated = updateTitle(conn, id, title)
                    if (updated) updateConversationTimestamp(conn, id, now)
                    conn.commit()
                    updated
                } catch (t: Throwable) {
                    runCatching { conn.rollback() }
                    throw t
                } finally {
                    conn.autoCommit = true
                }
            }
            if (!ok) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("conversation not found", 404))
                return@post
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
        }

        post("/{id}/move") {
            val id = call.parameters["id"].orEmpty()
            if (id.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing id", 400))
                return@post
            }
            val req = call.receive<MoveConversationRequest>()
            val dbPath = dataDir.resolve("db").resolve("rikka_hub.db")
            val now = System.currentTimeMillis()
            val ok = withSqliteConnection(dbPath) { conn ->
                conn.autoCommit = false
                try {
                    val updated = moveConversation(conn, id, req.assistantId)
                    if (updated) updateConversationTimestamp(conn, id, now)
                    conn.commit()
                    updated
                } catch (t: Throwable) {
                    runCatching { conn.rollback() }
                    throw t
                } finally {
                    conn.autoCommit = true
                }
            }
            if (!ok) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("conversation not found", 404))
                return@post
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
        }

        post("/{id}/fork") {
            val id = call.parameters["id"].orEmpty()
            if (id.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing id", 400))
                return@post
            }
            call.receive<ForkConversationRequest>()
            val dbPath = dataDir.resolve("db").resolve("rikka_hub.db")
            val now = System.currentTimeMillis()
            val forkId = withSqliteConnection(dbPath) { conn ->
                conn.autoCommit = false
                try {
                    val fork = forkConversation(conn, id, now)
                    conn.commit()
                    fork
                } catch (t: Throwable) {
                    runCatching { conn.rollback() }
                    throw t
                } finally {
                    conn.autoCommit = true
                }
            }
            if (forkId == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("conversation not found", 404))
                return@post
            }
            call.respond(HttpStatusCode.Created, ForkConversationResponse(conversationId = forkId))
        }

        post("/{id}/nodes/{nodeId}/select") {
            val conversationId = call.parameters["id"].orEmpty()
            val nodeId = call.parameters["nodeId"].orEmpty()
            if (conversationId.isBlank() || nodeId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing id", 400))
                return@post
            }
            val req = call.receive<SelectMessageNodeRequest>()
            val dbPath = dataDir.resolve("db").resolve("rikka_hub.db")
            withSqliteConnection(dbPath) { conn ->
                updateNodeSelectIndex(conn, conversationId, nodeId, req.selectIndex)
            }
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }

        post("/{id}/regenerate") {
            val id = call.parameters["id"].orEmpty()
            if (id.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing id", 400))
                return@post
            }

            val req = call.receive<RegenerateRequest>()
            if (req.messageId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing messageId", 400))
                return@post
            }

            generationManager.stop(id)

            val dbPath = dataDir.resolve("db").resolve("rikka_hub.db")

            data class RegenPlan(
                val role: String,
                val nodeId: String,
                val nodeIndex: Int,
            )

            val plan = withSqliteConnection(dbPath) { conn ->
                conn.autoCommit = false
                try {
                    val target = conn.prepareStatement(
                        """
                        SELECT id, node_index, messages
                        FROM message_node
                        WHERE conversation_id = ?
                        ORDER BY node_index ASC
                        """.trimIndent()
                    ).use { ps ->
                        ps.setString(1, id)
                        ps.executeQuery().use { rs ->
                            var found: RegenPlan? = null
                            while (rs.next() && found == null) {
                                val nodeId = rs.getString("id")
                                val nodeIndex = rs.getInt("node_index")
                                val messagesJson = rs.getString("messages")
                                val arr = runCatching { json.parseToJsonElement(messagesJson) as JsonArray }.getOrNull() ?: continue
                                for (el in arr) {
                                    val obj = el as? JsonObject ?: continue
                                    val msgId = obj["id"]?.jsonPrimitive?.contentOrNull
                                    if (msgId != req.messageId) continue
                                    val role = obj["role"]?.jsonPrimitive?.contentOrNull ?: ""
                                    found = RegenPlan(role = role, nodeId = nodeId, nodeIndex = nodeIndex)
                                    break
                                }
                            }
                            found
                        }
                    }
                    if (target == null) {
                        conn.rollback()
                        return@withSqliteConnection null
                    }

                    conn.prepareStatement(
                        """
                        SELECT id
                        FROM message_node
                        WHERE conversation_id = ? AND node_index > ?
                        ORDER BY node_index DESC
                        """.trimIndent()
                    ).use { ps ->
                        ps.setString(1, id)
                        ps.setInt(2, target.nodeIndex)
                        ps.executeQuery().use { rs ->
                            val toDelete = mutableListOf<String>()
                            while (rs.next()) {
                                toDelete.add(rs.getString("id"))
                            }
                            for (nodeId in toDelete) {
                                conn.prepareStatement("DELETE FROM message_node WHERE id = ?").use { dp ->
                                    dp.setString(1, nodeId)
                                    dp.executeUpdate()
                                }
                                removeNodeId(conn, id, nodeId)
                            }
                        }
                    }

                    if (target.role.equals("ASSISTANT", ignoreCase = true)) {
                        val row = conn.prepareStatement("SELECT messages FROM message_node WHERE id = ?").use { ps ->
                            ps.setString(1, target.nodeId)
                            ps.executeQuery().use { rs ->
                                if (!rs.next()) return@use null
                                rs.getString("messages")
                            }
                        } ?: run {
                            conn.rollback()
                            return@withSqliteConnection null
                        }

                        val arr = runCatching { json.parseToJsonElement(row) as JsonArray }.getOrNull() ?: JsonArray(emptyList())
                        val messageId = UUID.randomUUID().toString()
                        val message = JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(messageId),
                                "role" to JsonPrimitive("ASSISTANT"),
                                "parts" to JsonArray(
                                    listOf(
                                        JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("text"),
                                                "text" to JsonPrimitive(""),
                                            )
                                        )
                                    )
                                ),
                                "annotations" to JsonArray(emptyList()),
                                "createdAt" to JsonPrimitive(LocalDateTime.now().toString()),
                                "finishedAt" to JsonNull,
                                "modelId" to JsonNull,
                                "usage" to JsonNull,
                                "translation" to JsonNull,
                            )
                        )
                        val nextArr = JsonArray(arr + message)
                        val nextSelect = arr.size

                        conn.prepareStatement("UPDATE message_node SET messages = ?, select_index = ? WHERE id = ?").use { ps ->
                            ps.setString(1, json.encodeToString(nextArr))
                            ps.setInt(2, nextSelect)
                            ps.setString(3, target.nodeId)
                            ps.executeUpdate()
                        }
                    }

                    updateConversationTimestamp(conn, id, System.currentTimeMillis())
                    conn.commit()
                    target
                } catch (t: Throwable) {
                    runCatching { conn.rollback() }
                    throw t
                } finally {
                    conn.autoCommit = true
                }
            }

            if (plan == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("message not found", 404))
                return@post
            }

            if (plan.role.equals("ASSISTANT", ignoreCase = true)) {
                generationManager.startRegenerate(id, plan.nodeId, plan.nodeIndex)
            } else {
                generationManager.start(id)
            }

            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }

        post("/{id}/stop") {
            val id = call.parameters["id"].orEmpty()
            if (id.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing id", 400))
                return@post
            }
            generationManager.stop(id)
            call.respond(HttpStatusCode.OK, mapOf("status" to "stopped"))
        }

        post("/{id}/tool-approval") {
            val id = call.parameters["id"].orEmpty()
            if (id.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing id", 400))
                return@post
            }
            val req = call.receive<ToolApprovalRequest>()
            generationManager.approveToolCall(id, req.toolCallId, req.approved, req.reason)
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }

        sse("/{id}/stream") {
            heartbeat {
                period = 1.seconds
            }

            val id = call.parameters["id"].orEmpty()
            if (id.isBlank()) {
                send(data = json.encodeToString(mapOf("type" to "error", "message" to "missing id")), event = "error")
                return@sse
            }

            val dbPath = dataDir.resolve("db").resolve("rikka_hub.db")
            val conversation = withSqliteConnection(dbPath) { conn ->
                val base = queryConversationById(conn, id) ?: return@withSqliteConnection null
                val nodes = queryNodesOfConversation(conn, id)
                base.copy(messages = nodes, isGenerating = generationManager.isGenerating(id))
            }

            if (conversation == null) {
                send(data = json.encodeToString(mapOf("type" to "error", "message" to "conversation not found")), event = "error")
                return@sse
            }

            val snapshot = ConversationSnapshotEventDto(
                type = "snapshot",
                seq = 1,
                conversation = conversation,
                serverTime = System.currentTimeMillis(),
            )
            send(data = json.encodeToString(snapshot), event = "snapshot")

            var seq = 1L
            var lastUpdateAt = conversation.updateAt
            while (true) {
                delay(2.seconds)
                val updateAt = withSqliteConnection(dbPath) { conn ->
                    queryConversationUpdateAt(conn, id) ?: lastUpdateAt
                }
                if (updateAt != lastUpdateAt) {
                    val updated = withSqliteConnection(dbPath) { conn ->
                        val base = queryConversationById(conn, id) ?: return@withSqliteConnection null
                        val nodes = queryNodesOfConversation(conn, id)
                        base.copy(messages = nodes, isGenerating = generationManager.isGenerating(id))
                    } ?: continue
                    lastUpdateAt = updated.updateAt
                    seq += 1
                    send(
                        data = json.encodeToString(
                            ConversationSnapshotEventDto(
                                type = "snapshot",
                                seq = seq,
                                conversation = updated,
                                serverTime = System.currentTimeMillis(),
                            )
                        ),
                        event = "snapshot"
                    )
                }
            }
        }

        sse("/stream") {
            heartbeat {
                period = 15.seconds
            }
            val assistantId = readAssistantIdFromSettingsOrNull(dataDir) ?: ""
            while (true) {
                val payload = json.encodeToString(
                    ConversationListInvalidateEventDto(
                        type = "invalidate",
                        assistantId = assistantId,
                        timestamp = System.currentTimeMillis(),
                    )
                )
                send(data = payload, event = "invalidate")
                delay(15.seconds)
            }
        }
    }
}

private fun ensureConversationExists(conn: Connection, id: String, assistantId: String, now: Long) {
    conn.prepareStatement("SELECT 1 FROM conversationentity WHERE id = ? LIMIT 1").use { ps ->
        ps.setString(1, id)
        ps.executeQuery().use { rs ->
            if (rs.next()) return
        }
    }

    val insertSql = """
        INSERT INTO conversationentity (
            id, assistant_id, title, nodes, create_at, update_at, truncate_index, suggestions, is_pinned
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    """.trimIndent()
    conn.prepareStatement(insertSql).use { ps ->
        ps.setString(1, id)
        ps.setString(2, assistantId)
        ps.setString(3, "New Chat")
        ps.setString(4, "[]")
        ps.setLong(5, now)
        ps.setLong(6, now)
        ps.setInt(7, -1)
        ps.setString(8, "[]")
        ps.setBoolean(9, false)
        ps.executeUpdate()
    }
}

private fun appendMessage(conn: Connection, conversationId: String, message: JsonObject) {
    val selectSql = """
        SELECT id, node_index, messages
        FROM message_node
        WHERE conversation_id = ?
        ORDER BY node_index DESC
        LIMIT 1
    """.trimIndent()
    conn.prepareStatement(selectSql).use { ps ->
        ps.setString(1, conversationId)
        ps.executeQuery().use { rs ->
            if (!rs.next()) {
                insertNewNode(conn, conversationId, nodeIndex = 0, messages = JsonArray(listOf(message)), selectIndex = 0)
                return
            }
            val nodeId = rs.getString("id")
            val messagesJson = rs.getString("messages")
            val arr = runCatching { json.parseToJsonElement(messagesJson) as JsonArray }.getOrElse { JsonArray(emptyList()) }
            val updated = JsonArray(arr + message)
            conn.prepareStatement("UPDATE message_node SET messages = ? WHERE id = ?").use { ups ->
                ups.setString(1, json.encodeToString(updated))
                ups.setString(2, nodeId)
                ups.executeUpdate()
            }
        }
    }
}

private fun insertNewNode(conn: Connection, conversationId: String, nodeIndex: Int, messages: JsonArray, selectIndex: Int): String {
    val sql = """
        INSERT INTO message_node (id, conversation_id, node_index, messages, select_index)
        VALUES (?, ?, ?, ?, ?)
    """.trimIndent()
    val id = UUID.randomUUID().toString()
    conn.prepareStatement(sql).use { ps ->
        ps.setString(1, id)
        ps.setString(2, conversationId)
        ps.setInt(3, nodeIndex)
        ps.setString(4, json.encodeToString(messages))
        ps.setInt(5, selectIndex)
        ps.executeUpdate()
    }
    return id
}

private fun nextNodeIndex(conn: Connection, conversationId: String): Int {
    conn.prepareStatement("SELECT COALESCE(MAX(node_index), -1) AS m FROM message_node WHERE conversation_id = ?").use { ps ->
        ps.setString(1, conversationId)
        ps.executeQuery().use { rs ->
            rs.next()
            return rs.getInt("m") + 1
        }
    }
}

private fun appendNodeId(conn: Connection, conversationId: String, nodeId: String) {
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

private fun updateConversationTimestamp(conn: Connection, conversationId: String, now: Long) {
    conn.prepareStatement("UPDATE conversationentity SET update_at = ? WHERE id = ?").use { ps ->
        ps.setLong(1, now)
        ps.setString(2, conversationId)
        ps.executeUpdate()
    }
}

private fun queryConversationUpdateAt(conn: Connection, id: String): Long? {
    conn.prepareStatement("SELECT update_at FROM conversationentity WHERE id = ?").use { ps ->
        ps.setString(1, id)
        ps.executeQuery().use { rs ->
            return if (rs.next()) rs.getLong("update_at") else null
        }
    }
}

private fun togglePinned(conn: Connection, id: String): Boolean {
    val current = conn.prepareStatement("SELECT is_pinned FROM conversationentity WHERE id = ?").use { ps ->
        ps.setString(1, id)
        ps.executeQuery().use { rs ->
            if (!rs.next()) return false
            rs.getBoolean("is_pinned")
        }
    }
    conn.prepareStatement("UPDATE conversationentity SET is_pinned = ? WHERE id = ?").use { ps ->
        ps.setBoolean(1, !current)
        ps.setString(2, id)
        ps.executeUpdate()
    }
    return true
}

private fun updateTitle(conn: Connection, id: String, title: String): Boolean {
    conn.prepareStatement("UPDATE conversationentity SET title = ? WHERE id = ?").use { ps ->
        ps.setString(1, title)
        ps.setString(2, id)
        return ps.executeUpdate() > 0
    }
}

private fun moveConversation(conn: Connection, id: String, assistantId: String): Boolean {
    conn.prepareStatement("UPDATE conversationentity SET assistant_id = ? WHERE id = ?").use { ps ->
        ps.setString(1, assistantId)
        ps.setString(2, id)
        return ps.executeUpdate() > 0
    }
}

private fun deleteConversation(conn: Connection, id: String): Boolean {
    conn.prepareStatement("DELETE FROM message_node WHERE conversation_id = ?").use { ps ->
        ps.setString(1, id)
        ps.executeUpdate()
    }
    conn.prepareStatement("DELETE FROM conversationentity WHERE id = ?").use { ps ->
        ps.setString(1, id)
        return ps.executeUpdate() > 0
    }
}

private fun forkConversation(conn: Connection, sourceConversationId: String, now: Long): String? {
    data class SourceConversation(
        val assistantId: String,
        val title: String,
        val truncateIndex: Int,
        val suggestions: String,
    )

    val source = conn.prepareStatement(
        """
            SELECT assistant_id, title, truncate_index, suggestions
            FROM conversationentity
            WHERE id = ?
        """.trimIndent()
    ).use { ps ->
        ps.setString(1, sourceConversationId)
        ps.executeQuery().use { rs ->
            if (!rs.next()) return null
            SourceConversation(
                assistantId = rs.getString("assistant_id"),
                title = rs.getString("title"),
                truncateIndex = rs.getInt("truncate_index"),
                suggestions = rs.getString("suggestions") ?: "[]",
            )
        }
    }

    val newId = UUID.randomUUID().toString()
    conn.prepareStatement(
        """
            INSERT INTO conversationentity (
                id, assistant_id, title, nodes, create_at, update_at, truncate_index, suggestions, is_pinned
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
    ).use { ps ->
        ps.setString(1, newId)
        ps.setString(2, source.assistantId)
        ps.setString(3, "${source.title} (fork)")
        ps.setString(4, "[]")
        ps.setLong(5, now)
        ps.setLong(6, now)
        ps.setInt(7, source.truncateIndex)
        ps.setString(8, source.suggestions)
        ps.setBoolean(9, false)
        ps.executeUpdate()
    }

    conn.prepareStatement(
        """
            SELECT node_index, messages, select_index
            FROM message_node
            WHERE conversation_id = ?
            ORDER BY node_index ASC
        """.trimIndent()
    ).use { ps ->
        ps.setString(1, sourceConversationId)
        ps.executeQuery().use { rs ->
            while (rs.next()) {
                insertNewNode(
                    conn = conn,
                    conversationId = newId,
                    nodeIndex = rs.getInt("node_index"),
                    messages = runCatching { json.parseToJsonElement(rs.getString("messages")) as JsonArray }
                        .getOrElse { JsonArray(emptyList()) },
                    selectIndex = rs.getInt("select_index"),
                )
            }
        }
    }
    return newId
}

private fun updateNodeSelectIndex(conn: Connection, conversationId: String, nodeId: String, selectIndex: Int) {
    conn.prepareStatement(
        """
            UPDATE message_node
            SET select_index = ?
            WHERE id = ? AND conversation_id = ?
        """.trimIndent()
    ).use { ps ->
        ps.setInt(1, selectIndex)
        ps.setString(2, nodeId)
        ps.setString(3, conversationId)
        ps.executeUpdate()
    }
}

private fun editMessageParts(conn: Connection, conversationId: String, messageId: String, parts: List<JsonElement>) {
    val sql = """
        SELECT id, messages
        FROM message_node
        WHERE conversation_id = ?
        ORDER BY node_index ASC
    """.trimIndent()
    conn.prepareStatement(sql).use { ps ->
        ps.setString(1, conversationId)
        ps.executeQuery().use { rs ->
            while (rs.next()) {
                val nodeId = rs.getString("id")
                val messagesJson = rs.getString("messages")
                val arr = runCatching { json.parseToJsonElement(messagesJson) as JsonArray }
                    .getOrElse { continue }

                val updated = arr.map { el ->
                    val obj = el as? JsonObject ?: return@map el
                    val id = obj["id"].stringOrNull() ?: return@map el
                    if (id != messageId) {
                        el
                    } else {
                        JsonObject(obj.toMutableMap().apply {
                            put("parts", JsonArray(parts))
                        })
                    }
                }

                if (updated != arr) {
                    conn.prepareStatement("UPDATE message_node SET messages = ? WHERE id = ?").use { ups ->
                        ups.setString(1, json.encodeToString(JsonArray(updated)))
                        ups.setString(2, nodeId)
                        ups.executeUpdate()
                    }
                    return
                }
            }
        }
    }
}

private fun deleteMessage(conn: Connection, conversationId: String, messageId: String) {
    val sql = """
        SELECT id, messages
        FROM message_node
        WHERE conversation_id = ?
        ORDER BY node_index ASC
    """.trimIndent()
    conn.prepareStatement(sql).use { ps ->
        ps.setString(1, conversationId)
        ps.executeQuery().use { rs ->
            while (rs.next()) {
                val nodeId = rs.getString("id")
                val messagesJson = rs.getString("messages")
                val arr = runCatching { json.parseToJsonElement(messagesJson) as JsonArray }
                    .getOrElse { continue }

                val updated = arr.filterNot { el ->
                    val obj = el as? JsonObject ?: return@filterNot false
                    obj["id"].stringOrNull() == messageId
                }

                if (updated.size == arr.size) {
                    continue
                }

                if (updated.isEmpty()) {
                    conn.prepareStatement("DELETE FROM message_node WHERE id = ?").use { del ->
                        del.setString(1, nodeId)
                        del.executeUpdate()
                    }
                    removeNodeId(conn, conversationId, nodeId)
                    compactNodeIndexes(conn, conversationId)
                } else {
                    val nextSelectIndex = 0
                    conn.prepareStatement("UPDATE message_node SET messages = ?, select_index = ? WHERE id = ?").use { ups ->
                        ups.setString(1, json.encodeToString(JsonArray(updated)))
                        ups.setInt(2, nextSelectIndex)
                        ups.setString(3, nodeId)
                        ups.executeUpdate()
                    }
                }
                return
            }
        }
    }
}

private fun removeNodeId(conn: Connection, conversationId: String, nodeId: String) {
    val nodesJson = conn.prepareStatement("SELECT nodes FROM conversationentity WHERE id = ?").use { ps ->
        ps.setString(1, conversationId)
        ps.executeQuery().use { rs ->
            if (!rs.next()) return
            rs.getString("nodes")
        }
    } ?: return

    val arr = runCatching { json.parseToJsonElement(nodesJson) as JsonArray }.getOrElse { return }
    val updated = arr.filterNot { el ->
        (el as? JsonPrimitive)?.contentOrNull == nodeId
    }
    if (updated.size == arr.size) return
    conn.prepareStatement("UPDATE conversationentity SET nodes = ? WHERE id = ?").use { ps ->
        ps.setString(1, json.encodeToString(JsonArray(updated)))
        ps.setString(2, conversationId)
        ps.executeUpdate()
    }
}

private fun compactNodeIndexes(conn: Connection, conversationId: String) {
    val sql = """
        SELECT id
        FROM message_node
        WHERE conversation_id = ?
        ORDER BY node_index ASC
    """.trimIndent()
    conn.prepareStatement(sql).use { ps ->
        ps.setString(1, conversationId)
        ps.executeQuery().use { rs ->
            var idx = 0
            while (rs.next()) {
                val id = rs.getString("id")
                conn.prepareStatement("UPDATE message_node SET node_index = ? WHERE id = ?").use { ups ->
                    ups.setInt(1, idx)
                    ups.setString(2, id)
                    ups.executeUpdate()
                }
                idx += 1
            }
        }
    }
}

private fun readAssistantIdFromSettingsOrNull(dataDir: Path): String? {
    val settingsPath = dataDir.resolve("settings.json")
    if (!Files.exists(settingsPath)) return null
    return runCatching {
        val text = Files.readString(settingsPath, StandardCharsets.UTF_8)
        val root = json.parseToJsonElement(text).jsonObject
        root["assistantId"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
}

private fun queryConversationPage(
    conn: Connection,
    offset: Int,
    limit: Int,
    query: String?,
    assistantId: String?,
): List<ConversationListDto> {
    val clauses = mutableListOf<String>()
    val params = mutableListOf<Any>()
    if (query != null) {
        clauses.add("title LIKE ?")
        params.add("%$query%")
    }
    if (!assistantId.isNullOrBlank()) {
        clauses.add("assistant_id = ?")
        params.add(assistantId)
    }
    val where = if (clauses.isEmpty()) "" else "WHERE ${clauses.joinToString(" AND ")}"
    val sql = """
        SELECT id, assistant_id, title, create_at, update_at, is_pinned
        FROM conversationentity
        $where
        ORDER BY is_pinned DESC, update_at DESC
        LIMIT ? OFFSET ?
    """.trimIndent()
    params.add(limit)
    params.add(offset)

    conn.prepareStatement(sql).use { ps ->
        params.forEachIndexed { index, value ->
            ps.setObject(index + 1, value)
        }
        ps.executeQuery().use { rs ->
            val out = mutableListOf<ConversationListDto>()
            while (rs.next()) {
                out.add(rs.toConversationListDto())
            }
            return out
        }
    }
}

private fun queryConversationById(conn: Connection, id: String): ConversationDto? {
    val sql = """
        SELECT id, assistant_id, title, create_at, update_at, truncate_index, suggestions, is_pinned
        FROM conversationentity
        WHERE id = ?
    """.trimIndent()
    conn.prepareStatement(sql).use { ps ->
        ps.setString(1, id)
        ps.executeQuery().use { rs ->
            if (!rs.next()) return null
            val suggestionsJson = rs.getString("suggestions") ?: "[]"
            val suggestions = parseStringArrayOrEmpty(suggestionsJson)
            return ConversationDto(
                id = rs.getString("id"),
                assistantId = rs.getString("assistant_id"),
                title = rs.getString("title"),
                messages = emptyList(),
                truncateIndex = rs.getInt("truncate_index"),
                chatSuggestions = suggestions,
                isPinned = rs.getBoolean("is_pinned"),
                createAt = rs.getLong("create_at"),
                updateAt = rs.getLong("update_at"),
                isGenerating = false,
            )
        }
    }
}

private fun queryNodesOfConversation(conn: Connection, conversationId: String): List<MessageNodeDto> {
    val sql = """
        SELECT id, node_index, messages, select_index
        FROM message_node
        WHERE conversation_id = ?
        ORDER BY node_index ASC
    """.trimIndent()
    conn.prepareStatement(sql).use { ps ->
        ps.setString(1, conversationId)
        ps.executeQuery().use { rs ->
            val nodes = mutableListOf<MessageNodeDto>()
            while (rs.next()) {
                val node = rs.toMessageNodeDto()
                if (node.messages.isNotEmpty()) {
                    nodes.add(node)
                }
            }
            return nodes
        }
    }
}

private fun ResultSet.toConversationListDto(): ConversationListDto {
    return ConversationListDto(
        id = getString("id"),
        assistantId = getString("assistant_id"),
        title = getString("title"),
        isPinned = getBoolean("is_pinned"),
        createAt = getLong("create_at"),
        updateAt = getLong("update_at"),
        isGenerating = false,
    )
}

private fun ResultSet.toMessageNodeDto(): MessageNodeDto {
    val messagesJson = getString("messages")
    val messages = parseMessages(messagesJson)
    return MessageNodeDto(
        id = getString("id"),
        messages = messages,
        selectIndex = getInt("select_index"),
    )
}

private fun parseMessages(messagesJson: String?): List<MessageDto> {
    if (messagesJson.isNullOrBlank()) return emptyList()
    val element = runCatching { json.parseToJsonElement(messagesJson) }.getOrNull() ?: return emptyList()
    val arr = element as? JsonArray ?: return emptyList()
    return arr.mapNotNull { toMessageDtoOrNull(it) }
}

private fun toMessageDtoOrNull(element: JsonElement): MessageDto? {
    val obj = element as? JsonObject ?: return null
    val id = obj["id"].stringOrNull() ?: return null
    val role = obj["role"].stringOrNull() ?: return null
    val parts = (obj["parts"] as? JsonArray)?.map { rewriteMessagePart(it) } ?: emptyList()
    val annotations = (obj["annotations"] as? JsonArray)?.toList() ?: emptyList()
    val createdAt = obj["createdAt"].stringOrNull() ?: ""
    val finishedAt = obj["finishedAt"].stringOrNull()
    val modelId = obj["modelId"].stringOrNull()
    val usage = obj["usage"]
    val translation = obj["translation"].stringOrNull()
    return MessageDto(
        id = id,
        role = role,
        parts = parts,
        annotations = annotations,
        createdAt = createdAt,
        finishedAt = finishedAt,
        modelId = modelId,
        usage = usage,
        translation = translation,
    )
}

private fun rewriteMessagePart(element: JsonElement): JsonElement {
    val obj = element as? JsonObject ?: return element

    val type = obj["type"].stringOrNull()
    val updated = obj.toMutableMap()

    val output = obj["output"] as? JsonArray
    if (output != null) {
        updated["output"] = JsonArray(output.map { rewriteMessagePart(it) })
    }

    if (type == null) return JsonObject(updated)
    val needsUrlRewrite = type == "image" || type == "video" || type == "audio" || type == "document"
    if (!needsUrlRewrite) return JsonObject(updated)

    val url = obj["url"].stringOrNull() ?: return JsonObject(updated)
    val normalized = url.trim()
    if (
        normalized.startsWith("http://") ||
        normalized.startsWith("https://") ||
        normalized.startsWith("/api/") ||
        normalized.startsWith("data:") ||
        normalized.startsWith("blob:")
    ) {
        return JsonObject(updated)
    }

    val fileId = (obj["metadata"] as? JsonObject)
        ?.get("fileId")
        .stringOrNull()
        ?.toLongOrNull()

    val newUrl = if (fileId != null) {
        "/api/files/id/$fileId"
    } else {
        val idx = normalized.indexOf("upload/")
        val relativePath = when {
            normalized.startsWith("upload/") -> normalized
            idx >= 0 -> normalized.substring(idx)
            normalized.contains("/") -> "upload/${normalized.substringAfterLast('/')}"
            else -> "upload/$normalized"
        }
        "/api/files/path/$relativePath"
    }

    updated["url"] = JsonPrimitive(newUrl)
    return JsonObject(updated)
}

private fun JsonElement?.stringOrNull(): String? {
    val prim = this as? JsonPrimitive ?: return null
    return prim.contentOrNull
}

private fun parseStringArrayOrEmpty(jsonText: String): List<String> {
    val element = runCatching { json.parseToJsonElement(jsonText) }.getOrNull() ?: return emptyList()
    val arr = element as? JsonArray ?: return emptyList()
    return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}

private fun searchMessages(conn: Connection, query: String, limit: Int): List<MessageSearchResultDto> {
    val sql = """
        SELECT mn.id AS node_id,
               mn.conversation_id AS conversation_id,
               ce.title AS title,
               ce.update_at AS update_at,
               mn.messages AS messages
        FROM message_node mn
        JOIN conversationentity ce ON ce.id = mn.conversation_id
        WHERE mn.messages LIKE ? OR ce.title LIKE ?
        ORDER BY ce.update_at DESC
        LIMIT ?
    """.trimIndent()
    conn.prepareStatement(sql).use { ps ->
        ps.setString(1, "%$query%")
        ps.setString(2, "%$query%")
        ps.setInt(3, limit)
        ps.executeQuery().use { rs ->
            val out = mutableListOf<MessageSearchResultDto>()
            while (rs.next()) {
                val nodeId = rs.getString("node_id")
                val conversationId = rs.getString("conversation_id")
                val title = rs.getString("title")
                val updateAt = rs.getLong("update_at")
                val messagesJson = rs.getString("messages")
                val match = findFirstTextMatch(messagesJson, query)
                val messageId = match?.first ?: continue
                val snippet = match.second
                out.add(
                    MessageSearchResultDto(
                        nodeId = nodeId,
                        messageId = messageId,
                        conversationId = conversationId,
                        title = title,
                        updateAt = updateAt,
                        snippet = snippet,
                    )
                )
            }
            return out
        }
    }
}

private fun findFirstTextMatch(messagesJson: String?, query: String): Pair<String, String>? {
    if (messagesJson.isNullOrBlank()) return null
    val element = runCatching { json.parseToJsonElement(messagesJson) }.getOrNull() ?: return null
    val arr = element as? JsonArray ?: return null

    val q = query.lowercase()
    for (msg in arr) {
        val obj = msg as? JsonObject ?: continue
        val msgId = obj["id"].stringOrNull() ?: continue
        val parts = obj["parts"] as? JsonArray ?: continue
        for (part in parts) {
            val p = part as? JsonObject ?: continue
            val type = p["type"].stringOrNull() ?: continue
            if (type != "text") continue
            val text = p["text"].stringOrNull() ?: continue
            val idx = text.lowercase().indexOf(q)
            if (idx >= 0) {
                val start = (idx - 30).coerceAtLeast(0)
                val end = (idx + q.length + 60).coerceAtMost(text.length)
                val snippet = text.substring(start, end)
                return msgId to snippet
            }
        }
    }
    return null
}

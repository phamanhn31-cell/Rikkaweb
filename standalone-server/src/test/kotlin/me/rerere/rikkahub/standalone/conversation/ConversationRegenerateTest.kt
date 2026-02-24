package me.rerere.rikkahub.standalone.conversation

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.standalone.ai.GenerationManager
import me.rerere.rikkahub.standalone.api.conversationRoutes
import me.rerere.rikkahub.standalone.db.withSqliteConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.LocalDateTime

class ConversationRegenerateTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    @Test
    fun `regenerate from user truncates downstream nodes`() {
        val dataDir = Files.createTempDirectory("regen")
        val dbPath = dataDir.resolve("db").resolve("rikka_hub.db")

        val conversationId = "c1"
        val node0 = "n0"
        val node1 = "n1"
        val node2 = "n2"
        val node3 = "n3"

        val userMessageId = "m0"

        seedConversation(
            dbPath = dbPath,
            conversationId = conversationId,
            nodes = listOf(node0, node1, node2, node3),
            messagesByNode = mapOf(
                node0 to JsonArray(listOf(newMessage(userMessageId, "USER", "hi"))),
                node1 to JsonArray(listOf(newMessage("m1", "ASSISTANT", "a"))),
                node2 to JsonArray(listOf(newMessage("m2", "USER", "u2"))),
                node3 to JsonArray(listOf(newMessage("m3", "ASSISTANT", "a2"))),
            )
        )

        testApplication {
            application {
                install(SSE)
                install(ContentNegotiation) { json(this@ConversationRegenerateTest.json) }
                routing {
                    route("/api") {
                        conversationRoutes(dataDir, GenerationManager(dataDir))
                    }
                }
            }
            startApplication()

            val resp = client.post("/api/conversations/$conversationId/regenerate") {
                contentType(ContentType.Application.Json)
                setBody("{\"messageId\":\"$userMessageId\"}")
            }
            assertEquals(HttpStatusCode.Accepted, resp.status)

            Thread.sleep(250)

            withSqliteConnection(dbPath) { conn ->
                val ids = conn.prepareStatement(
                    "SELECT id, node_index FROM message_node WHERE conversation_id = ? ORDER BY node_index ASC"
                ).use { ps ->
                    ps.setString(1, conversationId)
                    ps.executeQuery().use { rs ->
                        val out = mutableListOf<Pair<String, Int>>()
                        while (rs.next()) out.add(rs.getString("id") to rs.getInt("node_index"))
                        out
                    }
                }

                assertTrue(ids.any { it.first == node0 })
                assertTrue(ids.none { it.first == node2 })
                assertTrue(ids.none { it.first == node3 })
                assertTrue(ids.size <= 2)
            }
        }
    }

    @Test
    fun `regenerate from assistant appends alternative and overwrites downstream`() {
        val dataDir = Files.createTempDirectory("regen")
        val dbPath = dataDir.resolve("db").resolve("rikka_hub.db")

        val conversationId = "c2"
        val node0 = "n0"
        val node1 = "n1"
        val node2 = "n2"
        val node3 = "n3"

        val assistantMessageId = "m1"

        seedConversation(
            dbPath = dbPath,
            conversationId = conversationId,
            nodes = listOf(node0, node1, node2, node3),
            messagesByNode = mapOf(
                node0 to JsonArray(listOf(newMessage("m0", "USER", "hi"))),
                node1 to JsonArray(listOf(newMessage(assistantMessageId, "ASSISTANT", "a"))),
                node2 to JsonArray(listOf(newMessage("m2", "USER", "u2"))),
                node3 to JsonArray(listOf(newMessage("m3", "ASSISTANT", "a2"))),
            )
        )

        testApplication {
            application {
                install(SSE)
                install(ContentNegotiation) { json(this@ConversationRegenerateTest.json) }
                routing {
                    route("/api") {
                        conversationRoutes(dataDir, GenerationManager(dataDir))
                    }
                }
            }
            startApplication()

            val resp = client.post("/api/conversations/$conversationId/regenerate") {
                contentType(ContentType.Application.Json)
                setBody("{\"messageId\":\"$assistantMessageId\"}")
            }
            assertEquals(HttpStatusCode.Accepted, resp.status)

            Thread.sleep(250)

            withSqliteConnection(dbPath) { conn ->
                val ids = conn.prepareStatement(
                    "SELECT id, node_index, messages, select_index FROM message_node WHERE conversation_id = ? ORDER BY node_index ASC"
                ).use { ps ->
                    ps.setString(1, conversationId)
                    ps.executeQuery().use { rs ->
                        val out = mutableListOf<Map<String, Any>>()
                        while (rs.next()) {
                            out.add(
                                mapOf(
                                    "id" to rs.getString("id"),
                                    "idx" to rs.getInt("node_index"),
                                    "messages" to rs.getString("messages"),
                                    "select" to rs.getInt("select_index"),
                                )
                            )
                        }
                        out
                    }
                }

                assertTrue(ids.none { it["id"] == node2 })
                assertTrue(ids.none { it["id"] == node3 })

                val node1Row = ids.first { it["id"] == node1 }
                val selectIndex = node1Row["select"] as Int
                val messagesJson = node1Row["messages"] as String
                val arr = (json.parseToJsonElement(messagesJson) as JsonArray)
                assertEquals(1, selectIndex)
                assertEquals(2, arr.size)

                val selected = arr[selectIndex].jsonObject
                val role = selected["role"]?.jsonPrimitive?.contentOrNull
                assertEquals("ASSISTANT", role)
                val finishedAt = selected["finishedAt"]
                assertNotNull(finishedAt)
            }
        }
    }

    private fun seedConversation(
        dbPath: java.nio.file.Path,
        conversationId: String,
        nodes: List<String>,
        messagesByNode: Map<String, JsonArray>,
    ) {
        val now = System.currentTimeMillis()
        val nodesJson = json.encodeToString(JsonArray(nodes.map { JsonPrimitive(it) }))
        withSqliteConnection(dbPath) { conn ->
            conn.prepareStatement(
                """
                INSERT INTO conversationentity(id, assistant_id, title, nodes, create_at, update_at, truncate_index, suggestions, is_pinned)
                VALUES(?,?,?,?,?,?,?,?,?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, conversationId)
                ps.setString(2, "a")
                ps.setString(3, "t")
                ps.setString(4, nodesJson)
                ps.setLong(5, now)
                ps.setLong(6, now)
                ps.setInt(7, -1)
                ps.setString(8, "[]")
                ps.setInt(9, 0)
                ps.executeUpdate()
            }

            nodes.forEachIndexed { idx, nodeId ->
                val messages = messagesByNode[nodeId] ?: JsonArray(emptyList())
                conn.prepareStatement(
                    "INSERT INTO message_node(id, conversation_id, node_index, messages, select_index) VALUES(?,?,?,?,?)"
                ).use { ps ->
                    ps.setString(1, nodeId)
                    ps.setString(2, conversationId)
                    ps.setInt(3, idx)
                    ps.setString(4, json.encodeToString(messages))
                    ps.setInt(5, 0)
                    ps.executeUpdate()
                }
            }
        }
    }

    private fun newMessage(id: String, role: String, text: String): JsonObject {
        return JsonObject(
            mapOf(
                "id" to JsonPrimitive(id),
                "role" to JsonPrimitive(role),
                "parts" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("text"),
                                "text" to JsonPrimitive(text),
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
    }
}

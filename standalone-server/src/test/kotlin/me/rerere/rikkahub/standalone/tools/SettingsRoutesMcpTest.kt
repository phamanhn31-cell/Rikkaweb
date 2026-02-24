package me.rerere.rikkahub.standalone.tools

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import me.rerere.rikkahub.standalone.api.SettingsFileStore
import me.rerere.rikkahub.standalone.api.settingsRoutes
import me.rerere.rikkahub.standalone.test.Evidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class SettingsRoutesMcpTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    @Test
    fun `assistant mcp update uses mcpServers field`() {
        val dataDir = Files.createTempDirectory("settings")

        val root = JsonObject(
            mapOf(
                "assistants" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive("a1"),
                                "name" to JsonPrimitive("A1"),
                                "systemPrompt" to JsonPrimitive(""),
                                "chatModelId" to JsonPrimitive("m1"),
                                "localTools" to JsonArray(listOf(JsonPrimitive("time_info"))),
                            )
                        )
                    )
                )
            )
        )
        Files.writeString(
            dataDir.resolve("settings.json"),
            json.encodeToString(JsonElement.serializer(), root),
            StandardCharsets.UTF_8
        )

        val store = SettingsFileStore(dataDir)

        testApplication {
            application {
                this.install(SSE)
                this.install(ContentNegotiation) {
                    json(this@SettingsRoutesMcpTest.json)
                }
                this.routing {
                    route("/api") {
                        settingsRoutes(store)
                    }
                }
            }

            startApplication()

            val resp = client.post("/api/settings/assistant/mcp") {
                contentType(ContentType.Application.Json)
                setBody(
                    "{\"assistantId\":\"a1\",\"mcpServerIds\":[\"s1\",\"s2\"]}"
                )
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            val text = resp.bodyAsText()
            assertTrue(text.contains("\"status\""))

            val updated = Files.readString(dataDir.resolve("settings.json"), StandardCharsets.UTF_8)
            val updatedRoot = json.parseToJsonElement(updated) as JsonObject
            val assistants = updatedRoot["assistants"] as JsonArray
            val a1 = assistants[0] as JsonObject

            assertTrue(a1.containsKey("mcpServers"))
            assertTrue(!a1.containsKey("mcpServerIds"))
            Evidence.write("task-settings-assistant-mcp.log", updated)
        }
    }
}

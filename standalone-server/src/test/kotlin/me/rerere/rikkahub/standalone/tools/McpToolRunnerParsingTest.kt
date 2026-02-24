package me.rerere.rikkahub.standalone.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.standalone.ai.tools.McpToolRunner
import me.rerere.rikkahub.standalone.test.Evidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class McpToolRunnerParsingTest {
    @Test
    fun `lists enabled tools from settings root`() {
        val dir = Files.createTempDirectory("mcp")
        val runner = McpToolRunner(dir)

        val toolSchema = JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to JsonObject(mapOf("x" to JsonObject(mapOf("type" to JsonPrimitive("string"))))),
                "required" to JsonArray(listOf(JsonPrimitive("x"))),
            )
        )

        val root = JsonObject(
            mapOf(
                "mcpServers" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("sse"),
                                "id" to JsonPrimitive("s1"),
                                "url" to JsonPrimitive("http://localhost:1234/sse"),
                                "commonOptions" to JsonObject(
                                    mapOf(
                                        "enable" to JsonPrimitive(true),
                                        "name" to JsonPrimitive("server"),
                                        "headers" to JsonArray(listOf(JsonArray(listOf(JsonPrimitive("X"), JsonPrimitive("Y"))))),
                                        "tools" to JsonArray(
                                            listOf(
                                                JsonObject(
                                                    mapOf(
                                                        "enable" to JsonPrimitive(true),
                                                        "name" to JsonPrimitive("echo"),
                                                        "description" to JsonPrimitive("echo tool"),
                                                        "needsApproval" to JsonPrimitive(true),
                                                        "inputSchema" to toolSchema,
                                                    )
                                                )
                                            )
                                        ),
                                    )
                                ),
                            )
                        )
                    )
                )
            )
        )

        val tools = runner.listTools(root, setOf("s1"))
        assertTrue(tools.containsKey("echo"))
        assertTrue(tools["echo"]!!.needsApproval)
        assertEquals("echo", tools["echo"]!!.name)

        Evidence.write("task-mcp-tools-parsing.log", tools["echo"]!!.inputSchema.toString())
    }
}

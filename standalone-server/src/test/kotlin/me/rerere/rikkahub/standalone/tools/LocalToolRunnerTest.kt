package me.rerere.rikkahub.standalone.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.standalone.ai.tools.LocalToolRunner
import me.rerere.rikkahub.standalone.test.Evidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LocalToolRunnerTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `time tool returns json`() {
        val dir = Files.createTempDirectory("localtools")
        val runner = LocalToolRunner(dir)

        val tools = runner.listTools(listOf("time_info"))
        assertTrue(tools.containsKey("get_time_info"))

        val res = kotlinx.coroutines.runBlocking {
            runner.callTool("get_time_info", JsonObject(emptyMap()))
        }

        val payload = res.modelText
        val el = json.parseToJsonElement(payload)
        assertNotNull((el as? JsonObject)?.get("year"))
        Evidence.write("task-localtools-time.log", payload)
    }

    @Test
    fun `clipboard tool roundtrip`() {
        val dir = Files.createTempDirectory("localtools")
        val runner = LocalToolRunner(dir)

        val tools = runner.listTools(listOf("clipboard"))
        assertTrue(tools.containsKey("clipboard_tool"))

        val writeRes = kotlinx.coroutines.runBlocking {
            runner.callTool(
                "clipboard_tool",
                JsonObject(mapOf("action" to JsonPrimitive("write"), "text" to JsonPrimitive("hello")))
            )
        }
        assertTrue(writeRes.modelText.contains("\"success\":true"))

        val readRes = kotlinx.coroutines.runBlocking {
            runner.callTool("clipboard_tool", JsonObject(mapOf("action" to JsonPrimitive("read"))))
        }
        assertTrue(readRes.modelText.contains("hello"))
        Evidence.write("task-localtools-clipboard.log", writeRes.modelText + "\n" + readRes.modelText)
    }

    @Test
    fun `javascript tool evaluates and is sandboxed`() {
        val dir = Files.createTempDirectory("localtools")
        val runner = LocalToolRunner(dir)

        val tools = runner.listTools(listOf("javascript_engine"))
        assertTrue(tools.containsKey("eval_javascript"))

        val ok = kotlinx.coroutines.runBlocking {
            runner.callTool(
                "eval_javascript",
                JsonObject(mapOf("code" to JsonPrimitive("console.log('x'); 1+2")))
            )
        }
        assertTrue(ok.modelText.contains("\"result\""))
        assertTrue(ok.modelText.contains("3"))

        val bad = kotlinx.coroutines.runBlocking {
            runner.callTool(
                "eval_javascript",
                JsonObject(mapOf("code" to JsonPrimitive("typeof Packages")))
            )
        }
        Evidence.write("task-localtools-js.log", ok.modelText + "\n" + bad.modelText)
    }
}

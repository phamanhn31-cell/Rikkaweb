package me.rerere.rikkahub.standalone.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class LocalToolRunner(private val dataDir: Path) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun listTools(enabledOptions: List<String>): Map<String, ToolSpec> {
        val enabled = enabledOptions.toSet()
        val out = LinkedHashMap<String, ToolSpec>()
        if (enabled.contains("time_info")) {
            out["get_time_info"] = ToolSpec(
                name = "get_time_info",
                description = "Get local time info for the server runtime.",
                inputSchema = JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap()))),
                needsApproval = false,
            )
        }
        if (enabled.contains("javascript_engine")) {
            out["eval_javascript"] = ToolSpec(
                name = "eval_javascript",
                description = "Evaluate JavaScript code in a sandboxed engine.",
                inputSchema = JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "properties" to JsonObject(mapOf("code" to JsonObject(mapOf("type" to JsonPrimitive("string"))))),
                        "required" to JsonArray(listOf(JsonPrimitive("code"))),
                    )
                ),
                needsApproval = false,
            )
        }
        if (enabled.contains("clipboard")) {
            out["clipboard_tool"] = ToolSpec(
                name = "clipboard_tool",
                description = "Read/write an internal clipboard stored in the data directory.",
                inputSchema = JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "properties" to JsonObject(
                            mapOf(
                                "action" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "text" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                            )
                        ),
                        "required" to JsonArray(listOf(JsonPrimitive("action"))),
                    )
                ),
                needsApproval = false,
            )
        }
        if (enabled.contains("tts")) {
            out["text_to_speech"] = ToolSpec(
                name = "text_to_speech",
                description = "Text-to-speech is not available in standalone mode; kept for compatibility.",
                inputSchema = JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "properties" to JsonObject(mapOf("text" to JsonObject(mapOf("type" to JsonPrimitive("string"))))),
                        "required" to JsonArray(listOf(JsonPrimitive("text"))),
                    )
                ),
                needsApproval = false,
            )
        }
        return out
    }

    suspend fun callTool(toolName: String, args: JsonObject): ToolExecutionResult {
        return when (toolName) {
            "get_time_info" -> ToolExecutionResult(
                outputParts = listOf(ToolJson.textPart(timeInfoJson())),
                modelText = timeInfoJson(),
            )

            "eval_javascript" -> {
                val code = args["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val payload = runCatching {
                    val (result, logs) = evalJs(code)
                    buildString {
                        append("{\"success\":true,\"result\":")
                        append(jsonString(result))
                        if (logs.isNotEmpty()) {
                            append(",\"logs\":[")
                            append(logs.joinToString(",") { jsonString(it) })
                            append("]")
                        }
                        append("}")
                    }
                }.getOrElse { t ->
                    val msg = (t.message ?: t::class.java.simpleName).take(300)
                    "{\"success\":false,\"error\":${jsonString(msg)}}"
                }
                ToolExecutionResult(outputParts = listOf(ToolJson.textPart(payload)), modelText = payload)
            }

            "clipboard_tool" -> {
                val action = args["action"]?.jsonPrimitive?.contentOrNull.orEmpty()
                when (action.lowercase()) {
                    "read" -> {
                        val text = readClipboard()
                        val payload = "{\"text\":${jsonString(text)}}"
                        ToolExecutionResult(listOf(ToolJson.textPart(payload)), payload)
                    }

                    "write" -> {
                        val text = args["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        writeClipboard(text)
                        val payload = "{\"success\":true,\"text\":${jsonString(text)}}"
                        ToolExecutionResult(listOf(ToolJson.textPart(payload)), payload)
                    }

                    else -> {
                        val payload = "{\"success\":false,\"error\":${jsonString("invalid action")}}"
                        ToolExecutionResult(listOf(ToolJson.textPart(payload)), payload)
                    }
                }
            }

            "text_to_speech" -> {
                val text = args["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val payload = "{\"success\":false,\"reason\":${jsonString("tts not supported")},\"text\":${jsonString(text)}}"
                ToolExecutionResult(listOf(ToolJson.textPart(payload)), payload)
            }

            else -> {
                val payload = "{\"success\":false,\"error\":${jsonString("unknown tool")}}"
                ToolExecutionResult(listOf(ToolJson.textPart(payload)), payload)
            }
        }
    }

    private fun timeInfoJson(): String {
        val now = ZonedDateTime.now()
        val zone = now.zone
        val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
        val weekdayShort = now.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US)
        val offsetSeconds = now.offset.totalSeconds
        val offsetHours = offsetSeconds / 3600
        val offsetMinutes = (kotlin.math.abs(offsetSeconds) % 3600) / 60
        val offsetStr = String.format("%+03d:%02d", offsetHours, offsetMinutes)
        return buildString {
            append("{")
            append("\"year\":${now.year},")
            append("\"month\":${now.monthValue},")
            append("\"day\":${now.dayOfMonth},")
            append("\"hour\":${now.hour},")
            append("\"minute\":${now.minute},")
            append("\"second\":${now.second},")
            append("\"weekday\":${jsonString(weekday)},")
            append("\"weekdayShort\":${jsonString(weekdayShort)},")
            append("\"timezone\":${jsonString(zone.id)},")
            append("\"utc_offset\":${jsonString(offsetStr)},")
            append("\"timestamp_ms\":${System.currentTimeMillis()}")
            append("}")
        }
    }

    private suspend fun evalJs(code: String): Pair<String, List<String>> {
        if (code.isBlank()) return "" to emptyList()
        return withContext(Dispatchers.Default) {
            val logs = mutableListOf<String>()
            val factory = object : ContextFactory() {
                override fun observeInstructionCount(cx: Context, instructionCount: Int) {
                    val deadline = cx.getThreadLocal(DEADLINE_KEY) as? Long ?: return
                    if (System.nanoTime() > deadline) {
                        throw IllegalStateException("execution timeout")
                    }
                }
            }

            val deadlineNs = System.nanoTime() + 2.seconds.inWholeNanoseconds
            val value = factory.call { cx ->
                cx.optimizationLevel = -1
                cx.instructionObserverThreshold = 10_000
                cx.putThreadLocal(DEADLINE_KEY, deadlineNs)
                cx.setClassShutter { false }
                val scope = cx.initStandardObjects()
                listOf("Packages", "java", "javax", "org", "com").forEach { key ->
                    if (ScriptableObject.hasProperty(scope, key)) {
                        ScriptableObject.deleteProperty(scope, key)
                    }
                }

                val console = object : ScriptableObject() {
                    override fun getClassName(): String = "console"
                }
                val logFn = object : BaseFunction() {
                    override fun call(
                        cx: Context,
                        scope: Scriptable,
                        thisObj: Scriptable,
                        args: Array<out Any?>,
                    ): Any {
                        logs.add(args.joinToString(" ") { it?.toString().orEmpty() })
                        return Context.getUndefinedValue()
                    }
                }
                console.defineProperty("log", logFn, ScriptableObject.DONTENUM)
                ScriptableObject.putProperty(scope, "console", console)

                cx.evaluateString(scope, code, "tool:eval_javascript", 1, null)
            }
            (value?.toString().orEmpty()) to logs
        }
    }

    private fun clipboardPath(): Path = dataDir.resolve("clipboard.txt")

    private fun readClipboard(): String {
        val p = clipboardPath()
        return if (Files.exists(p)) Files.readString(p, StandardCharsets.UTF_8) else ""
    }

    private fun writeClipboard(text: String) {
        Files.createDirectories(dataDir)
        Files.writeString(clipboardPath(), text, StandardCharsets.UTF_8)
    }
}

private const val DEADLINE_KEY = "deadline"

private fun jsonString(raw: String): String {
    val escaped = buildString {
        for (ch in raw) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
    return "\"$escaped\""
}

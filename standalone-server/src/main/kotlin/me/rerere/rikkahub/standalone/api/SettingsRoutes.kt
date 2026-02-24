package me.rerere.rikkahub.standalone.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

@Serializable
private data class UpdateAssistantRequest(val assistantId: String)

@Serializable
private data class UpdateSearchEnabledRequest(val enabled: Boolean)

@Serializable
private data class UpdateSearchServiceRequest(val index: Int)

@Serializable
private data class UpdateFavoriteModelsRequest(val modelIds: List<String>)

@Serializable
private data class UpdateAssistantModelRequest(val assistantId: String, val modelId: String)

@Serializable
private data class UpdateAssistantThinkingBudgetRequest(val assistantId: String, val thinkingBudget: Int? = null)

@Serializable
private data class UpdateAssistantMcpServersRequest(val assistantId: String, val mcpServerIds: List<String>)

@Serializable
private data class UpdateAssistantInjectionsRequest(
    val assistantId: String,
    val modeInjectionIds: List<String>,
    val lorebookIds: List<String>,
)

@Serializable
private data class UpdateBuiltInToolRequest(val modelId: String, val tool: String, val enabled: Boolean)

fun Route.settingsRoutes(settings: SettingsFileStore) {
    route("/settings") {
        sse("/stream") {
            heartbeat { period = 15.seconds }
            settings.flow.collect { element ->
                val payload = json.encodeToString(JsonElement.serializer(), element)
                send(data = payload, event = "update")
            }
        }

        post("/assistant") {
            val req = call.receive<UpdateAssistantRequest>()
            settings.update { root ->
                JsonObject(root.toMutableMap().apply {
                    put("assistantId", JsonPrimitive(req.assistantId))
                })
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/search/enabled") {
            val req = call.receive<UpdateSearchEnabledRequest>()
            settings.update { root ->
                JsonObject(root.toMutableMap().apply {
                    put("enableWebSearch", JsonPrimitive(req.enabled))
                })
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/search/service") {
            val req = call.receive<UpdateSearchServiceRequest>()
            require(req.index >= 0) { "index must be >= 0" }
            settings.update { root ->
                JsonObject(root.toMutableMap().apply {
                    put("searchServiceSelected", JsonPrimitive(req.index))
                })
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/favorite-models") {
            val req = call.receive<UpdateFavoriteModelsRequest>()
            settings.update { root ->
                JsonObject(root.toMutableMap().apply {
                    put("favoriteModels", JsonArray(req.modelIds.map { JsonPrimitive(it) }))
                })
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/assistant/model") {
            val req = call.receive<UpdateAssistantModelRequest>()
            settings.update { root ->
                val patchedAssistantRoot = patchAssistantObject(root, req.assistantId) { assistant ->
                    JsonObject(assistant.toMutableMap().apply {
                        put("chatModelId", JsonPrimitive(req.modelId))
                    })
                }
                JsonObject(patchedAssistantRoot.toMutableMap().apply {
                    put("chatModelId", JsonPrimitive(req.modelId))
                })
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/assistant/thinking-budget") {
            val req = call.receive<UpdateAssistantThinkingBudgetRequest>()
            if (!assistantExists(settings, req.assistantId)) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "assistant not found"))
                return@post
            }
            patchAssistant(settings, req.assistantId) { assistant ->
                JsonObject(assistant.toMutableMap().apply {
                    if (req.thinkingBudget == null) {
                        put("thinkingBudget", JsonNull)
                    } else {
                        put("thinkingBudget", JsonPrimitive(req.thinkingBudget))
                    }
                })
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/assistant/mcp") {
            val req = call.receive<UpdateAssistantMcpServersRequest>()
            if (!assistantExists(settings, req.assistantId)) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "assistant not found"))
                return@post
            }
            patchAssistant(settings, req.assistantId) { assistant ->
                JsonObject(assistant.toMutableMap().apply {
                    put("mcpServers", JsonArray(req.mcpServerIds.map { JsonPrimitive(it) }))
                    remove("mcpServerIds")
                })
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/assistant/injections") {
            val req = call.receive<UpdateAssistantInjectionsRequest>()
            patchAssistant(settings, req.assistantId) { assistant ->
                JsonObject(assistant.toMutableMap().apply {
                    put("modeInjectionIds", JsonArray(req.modeInjectionIds.map { JsonPrimitive(it) }))
                    put("lorebookIds", JsonArray(req.lorebookIds.map { JsonPrimitive(it) }))
                })
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        post("/model/built-in-tool") {
            call.receive<UpdateBuiltInToolRequest>()
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
    }
}

private fun patchAssistant(
    settings: SettingsFileStore,
    assistantId: String,
    transform: (JsonObject) -> JsonObject,
) {
    settings.update { root ->
        patchAssistantObject(root, assistantId, transform)
    }
}

private fun patchAssistantObject(
    root: JsonObject,
    assistantId: String,
    transform: (JsonObject) -> JsonObject,
): JsonObject {
    val assistants = root["assistants"] as? JsonArray ?: return root
    val migrated = assistants.map { el ->
        val obj = el as? JsonObject ?: return@map el
        val id = obj["id"]?.jsonPrimitive?.contentOrNull
        if (id == assistantId) transform(obj) else el
    }
    return if (migrated == assistants) {
        root
    } else {
        JsonObject(root.toMutableMap().apply {
            put("assistants", JsonArray(migrated))
        })
    }
}

private fun assistantExists(settings: SettingsFileStore, assistantId: String): Boolean {
    val root = settings.flow.value as? JsonObject ?: return false
    val assistants = root["assistants"] as? JsonArray ?: return false
    return assistants.any { el ->
        val obj = el as? JsonObject ?: return@any false
        obj["id"]?.jsonPrimitive?.contentOrNull == assistantId
    }
}

package me.rerere.rikkahub.standalone.ai

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class SettingsSnapshot(
    val assistantId: String,
    val chatModelId: String,
    val assistants: List<AssistantConfig>,
    val providers: List<ProviderConfig>,
) {
    fun resolveActiveAssistant(): AssistantConfig? {
        return assistants.firstOrNull { it.id == assistantId } ?: assistants.firstOrNull()
    }

    fun resolveSelectedModelId(): String {
        val assistant = resolveActiveAssistant()
        val fromAssistant = assistant?.chatModelId?.takeIf { it.isNotBlank() }
        return fromAssistant ?: chatModelId
    }
}

data class AssistantConfig(
    val id: String,
    val name: String,
    val systemPrompt: String,
    val chatModelId: String,
    val temperature: Double?,
    val topP: Double?,
    val maxTokens: Int?,
    val mcpServers: List<String>,
    val localTools: List<String>,
)

data class ProviderConfig(
    val type: String,
    val id: String,
    val enabled: Boolean,
    val name: String,
    val apiKey: String,
    val baseUrl: String,
    val chatCompletionsPath: String?,
    val models: List<ModelConfig>,
)

data class ModelConfig(
    val id: String,
    val enabled: Boolean,
    val modelId: String,
    val displayName: String,
)

data class SelectedModel(
    val provider: ProviderConfig,
    val model: ModelConfig,
)

object SettingsSnapshotReader {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun readRootFromDataDir(dataDir: Path): JsonObject {
        val settingsPath = dataDir.resolve("settings.json")
        val text = if (Files.exists(settingsPath)) {
            Files.readString(settingsPath, StandardCharsets.UTF_8)
        } else {
            "{}"
        }
        return runCatching { json.parseToJsonElement(text) as JsonObject }.getOrElse { JsonObject(emptyMap()) }
    }

    fun readFromDataDir(dataDir: Path): SettingsSnapshot {
        return parse(readRootFromDataDir(dataDir))
    }

    fun parse(root: JsonObject): SettingsSnapshot {
        val assistantId = root["assistantId"].stringOrEmpty()
        val chatModelId = root["chatModelId"].stringOrEmpty()
        val assistants = (root["assistants"] as? JsonArray)?.mapNotNull { parseAssistant(it) }.orEmpty()
        val providers = (root["providers"] as? JsonArray)?.mapNotNull { parseProvider(it) }.orEmpty()

        return SettingsSnapshot(
            assistantId = assistantId,
            chatModelId = chatModelId,
            assistants = assistants,
            providers = providers,
        )
    }

    fun selectModel(snapshot: SettingsSnapshot): SelectedModel? {
        val selectedUuid = snapshot.resolveSelectedModelId().trim()
        if (selectedUuid.isBlank()) return null

        for (provider in snapshot.providers) {
            if (!provider.enabled) continue
            val model = provider.models.firstOrNull { it.id == selectedUuid && it.enabled }
                ?: provider.models.firstOrNull { it.id == selectedUuid }
            if (model != null) {
                return SelectedModel(provider = provider, model = model)
            }
        }
        return null
    }

    private fun parseAssistant(el: JsonElement): AssistantConfig? {
        val obj = el as? JsonObject ?: return null
        val id = obj["id"].stringOrNull() ?: return null
        val name = obj["name"].stringOrNull() ?: "Assistant"
        val systemPrompt = obj["systemPrompt"].stringOrNull() ?: ""
        val chatModelId = obj["chatModelId"].stringOrNull() ?: ""
        val temperature = (obj["temperature"] as? JsonPrimitive)?.doubleOrNull
        val topP = (obj["topP"] as? JsonPrimitive)?.doubleOrNull
        val maxTokens = (obj["maxTokens"] as? JsonPrimitive)?.intOrNull

        val mcpServers = readStringArray(obj["mcpServers"])
            .ifEmpty { readStringArray(obj["mcpServerIds"]) }
        val localTools = readStringArray(obj["localTools"]).ifEmpty {
            readDiscriminatorTypeArray(obj["localTools"], discriminator = "type")
        }
        return AssistantConfig(
            id = id,
            name = name,
            systemPrompt = systemPrompt,
            chatModelId = chatModelId,
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
            mcpServers = mcpServers,
            localTools = localTools,
        )
    }

    private fun parseProvider(el: JsonElement): ProviderConfig? {
        val obj = el as? JsonObject ?: return null
        val type = obj["type"].stringOrNull() ?: return null
        val id = obj["id"].stringOrNull() ?: ""
        val enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true
        val name = obj["name"].stringOrNull() ?: type
        val apiKey = obj["apiKey"].stringOrNull() ?: ""
        val baseUrl = obj["baseUrl"].stringOrNull() ?: ""
        val chatCompletionsPath = obj["chatCompletionsPath"].stringOrNull()
        val models = (obj["models"] as? JsonArray)?.mapNotNull { parseModel(it) }.orEmpty()

        return ProviderConfig(
            type = type,
            id = id,
            enabled = enabled,
            name = name,
            apiKey = apiKey,
            baseUrl = baseUrl,
            chatCompletionsPath = chatCompletionsPath,
            models = models,
        )
    }

    private fun parseModel(el: JsonElement): ModelConfig? {
        val obj = el as? JsonObject ?: return null
        val id = obj["id"].stringOrNull() ?: return null
        val enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true
        val modelId = obj["modelId"].stringOrNull() ?: return null
        val displayName = obj["displayName"].stringOrNull() ?: modelId
        return ModelConfig(
            id = id,
            enabled = enabled,
            modelId = modelId,
            displayName = displayName,
        )
    }
}

private fun readStringArray(el: JsonElement?): List<String> {
    val arr = el as? JsonArray ?: return emptyList()
    return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.filter { it.isNotEmpty() }
}

private fun readDiscriminatorTypeArray(el: JsonElement?, discriminator: String): List<String> {
    val arr = el as? JsonArray ?: return emptyList()
    return arr.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        obj[discriminator]?.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }
}

private fun JsonElement?.stringOrNull(): String? {
    val prim = this as? JsonPrimitive ?: return null
    return prim.contentOrNull
}

private fun JsonElement?.stringOrEmpty(): String {
    return stringOrNull() ?: ""
}

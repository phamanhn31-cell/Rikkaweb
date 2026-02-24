package me.rerere.rikkahub.standalone.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.standalone.backup.SettingsJsonMigrator
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

class SettingsFileStore(private val dataDir: Path) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val settingsPath = dataDir.resolve("settings.json")

    private val _flow = MutableStateFlow(readSettingsOrEmptyAndMigrate())
    val flow: StateFlow<JsonElement> = _flow.asStateFlow()

    fun current(): JsonElement = _flow.value

    fun update(transform: (JsonObject) -> JsonObject) {
        val currentObj = _flow.value as? JsonObject ?: JsonObject(emptyMap())
        val next = transform(currentObj)
        writeAtomically(next)
        _flow.value = next
    }

    private fun readSettingsOrEmptyAndMigrate(): JsonElement {
        if (!settingsPath.exists()) {
            return JsonObject(emptyMap())
        }
        if (!settingsPath.isRegularFile()) {
            return JsonObject(emptyMap())
        }

        val text = runCatching { Files.readString(settingsPath, StandardCharsets.UTF_8) }.getOrNull()
            ?: return JsonObject(emptyMap())

        val originalElement = runCatching { json.parseToJsonElement(text) }.getOrNull()

        val migratedText = runCatching { SettingsJsonMigrator.migrate(text) }.getOrElse { text }
        val migratedElement = runCatching { json.parseToJsonElement(migratedText) }.getOrNull()

        val element = migratedElement ?: originalElement ?: JsonObject(emptyMap())
        if (migratedElement != null && migratedText != text) {
            runCatching { writeAtomically(migratedElement) }
        }
        return element
    }

    private fun writeAtomically(element: JsonElement) {
        dataDir.createDirectories()
        val tmpDir = dataDir.resolve("tmp")
        tmpDir.createDirectories()
        val tmpFile = tmpDir.resolve("settings.json.tmp")

        val text = json.encodeToString(element)
        Files.writeString(tmpFile, text, StandardCharsets.UTF_8)
        try {
            Files.move(tmpFile, settingsPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmpFile, settingsPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

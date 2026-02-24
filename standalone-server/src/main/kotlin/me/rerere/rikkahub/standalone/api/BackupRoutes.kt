package me.rerere.rikkahub.standalone.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.application.call
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readAvailable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.standalone.backup.BackupImportOptions
import me.rerere.rikkahub.standalone.backup.BackupZipImporter

private const val MAX_BACKUP_ZIP_BYTES: Long = 2L * 1024 * 1024 * 1024

@Serializable
private data class BackupImportResponse(
    val status: String,
    val importedSettings: Boolean,
    val importedDatabaseFiles: List<String>,
    val importedUploadFiles: Int,
    val skippedEntries: Int,
    val warnings: List<String>,
)

fun Route.backupRoutes(dataDir: Path, enabled: Boolean) {
    route("/backup") {
        post("/import") {
            if (!enabled) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorResponse("Enable JWT auth to use backup import API", 403)
                )
                return@post
            }

            val tmpDir = dataDir.resolve("tmp").createDirectoriesAndReturn()
            val tmpZip = tmpDir.resolve("import_${Instant.now().toEpochMilli()}.zip")

            val multipart = call.receiveMultipart()
            var written = 0L
            var hasFile = false

            while (true) {
                val part = multipart.readPart() ?: break
                try {
                    if (part !is PartData.FileItem) continue
                    if (hasFile) continue
                    hasFile = true

                    part.contentType?.toString()

                    val channel = part.provider()
                    tmpZip.parent.createDirectories()
                    Files.newOutputStream(tmpZip).use { out ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = channel.readAvailable(buffer, 0, buffer.size)
                            if (read <= 0) break
                            written += read
                            if (written > MAX_BACKUP_ZIP_BYTES) {
                                throw IllegalArgumentException("backup zip exceeds max size")
                            }
                            out.write(buffer, 0, read)
                        }
                    }
                } finally {
                    part.dispose()
                }
            }

            if (!hasFile || written == 0L) {
                runCatching { Files.deleteIfExists(tmpZip) }
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("No zip file uploaded", 400))
                return@post
            }

            val result = runCatching {
                BackupZipImporter.importZip(
                    zipFile = tmpZip,
                    dataDir = dataDir,
                    options = BackupImportOptions(overwriteExisting = true),
                )
            }.also {
                runCatching { Files.deleteIfExists(tmpZip) }
            }.getOrElse { t ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(t.message ?: "import failed", 400))
                return@post
            }

            call.respond(
                HttpStatusCode.OK,
                BackupImportResponse(
                    status = "ok",
                    importedSettings = result.importedSettings,
                    importedDatabaseFiles = result.importedDatabaseFiles,
                    importedUploadFiles = result.importedUploadFiles,
                    skippedEntries = result.skippedEntries,
                    warnings = result.warnings,
                )
            )
        }
    }
}

private fun Path.createDirectoriesAndReturn(): Path {
    createDirectories()
    return this
}

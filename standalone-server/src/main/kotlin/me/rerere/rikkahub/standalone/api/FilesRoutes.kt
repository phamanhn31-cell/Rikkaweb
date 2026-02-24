package me.rerere.rikkahub.standalone.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.application.call
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.sql.Connection
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.utils.io.readAvailable
import me.rerere.rikkahub.standalone.db.withSqliteConnection

private const val MAX_UPLOAD_FILE_SIZE_BYTES: Long = 20L * 1024 * 1024

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

@Serializable
data class UploadedFileDto(
    val id: Long,
    val fileName: String,
    val mime: String,
    val url: String,
)

@Serializable
data class UploadFilesResponseDto(
    val files: List<UploadedFileDto>,
)

private data class FileIndexEntry(
    val id: Long,
    val storedName: String,
    val fileName: String,
    val mime: String,
)

private class FileIndex(private val dataDir: Path) {
    private val indexPath = dataDir.resolve("tmp").resolve("files-index.json")
    private val seqPath = dataDir.resolve("tmp").resolve("files-seq.txt")

    private val nextId = AtomicLong(readSeq())
    private val entries = mutableMapOf<Long, FileIndexEntry>()

    init {
        loadIndex()
    }

    @Synchronized
    fun allocateId(): Long {
        val id = nextId.getAndIncrement()
        writeSeq(nextId.get())
        return id
    }

    @Synchronized
    fun put(entry: FileIndexEntry) {
        entries[entry.id] = entry
        writeIndex()
    }

    @Synchronized
    fun get(id: Long): FileIndexEntry? = entries[id]

    @Synchronized
    fun remove(id: Long): FileIndexEntry? {
        val removed = entries.remove(id)
        if (removed != null) {
            writeIndex()
        }
        return removed
    }

    private fun loadIndex() {
        if (!Files.exists(indexPath)) return
        runCatching {
            val text = Files.readString(indexPath)
            val obj = json.parseToJsonElement(text).jsonObject
            obj.forEach { (key, value) ->
                val id = key.toLongOrNull() ?: return@forEach
                val v = value.jsonObject
                val storedName = v["storedName"]?.jsonPrimitive?.content ?: return@forEach
                val fileName = v["fileName"]?.jsonPrimitive?.content ?: return@forEach
                val mime = v["mime"]?.jsonPrimitive?.content ?: "application/octet-stream"
                entries[id] = FileIndexEntry(id, storedName, fileName, mime)
            }
        }
    }

    private fun writeIndex() {
        indexPath.parent.createDirectories()
        val obj = JsonObject(
            entries.toSortedMap().entries.associate { (id, e) ->
                id.toString() to JsonObject(
                    mapOf(
                        "storedName" to JsonPrimitive(e.storedName),
                        "fileName" to JsonPrimitive(e.fileName),
                        "mime" to JsonPrimitive(e.mime),
                    )
                )
            }
        )
        val tmp = indexPath.resolveSibling("files-index.json.tmp")
        Files.writeString(tmp, json.encodeToString(obj))
        Files.move(tmp, indexPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun readSeq(): Long {
        if (!Files.exists(seqPath)) return 1L
        return Files.readString(seqPath).trim().toLongOrNull() ?: 1L
    }

    private fun writeSeq(value: Long) {
        seqPath.parent.createDirectories()
        Files.writeString(seqPath, value.toString())
    }
}

private data class ManagedFileRow(
    val id: Long,
    val relativePath: String,
    val displayName: String,
    val mimeType: String,
)

private fun dbPathOrNull(dataDir: Path): Path? {
    val path = dataDir.resolve("db").resolve("rikka_hub.db")
    return if (Files.exists(path)) path else null
}

private fun hasManagedFilesTable(conn: Connection): Boolean {
    conn.prepareStatement(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'managed_files' LIMIT 1"
    ).use { ps ->
        ps.executeQuery().use { rs ->
            return rs.next()
        }
    }
}

private fun insertManagedFile(
    conn: Connection,
    folder: String,
    relativePath: String,
    displayName: String,
    mimeType: String,
    sizeBytes: Long,
    now: Long,
): Long {
    val sql = """
        INSERT INTO managed_files (
            folder, relative_path, display_name, mime_type, size_bytes, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
    """.trimIndent()
    conn.prepareStatement(sql).use { ps ->
        ps.setString(1, folder)
        ps.setString(2, relativePath)
        ps.setString(3, displayName)
        ps.setString(4, mimeType)
        ps.setLong(5, sizeBytes)
        ps.setLong(6, now)
        ps.setLong(7, now)
        ps.executeUpdate()
    }
    conn.createStatement().use { st ->
        st.executeQuery("SELECT last_insert_rowid() AS id").use { rs ->
            rs.next()
            return rs.getLong("id")
        }
    }
}

private fun getManagedFileById(conn: Connection, id: Long): ManagedFileRow? {
    val sql = """
        SELECT id, relative_path, display_name, mime_type
        FROM managed_files
        WHERE id = ?
    """.trimIndent()
    conn.prepareStatement(sql).use { ps ->
        ps.setLong(1, id)
        ps.executeQuery().use { rs ->
            if (!rs.next()) return null
            return ManagedFileRow(
                id = rs.getLong("id"),
                relativePath = rs.getString("relative_path"),
                displayName = rs.getString("display_name"),
                mimeType = rs.getString("mime_type"),
            )
        }
    }
}

private fun deleteManagedFileById(conn: Connection, id: Long): ManagedFileRow? {
    val row = getManagedFileById(conn, id) ?: return null
    conn.prepareStatement("DELETE FROM managed_files WHERE id = ?").use { ps ->
        ps.setLong(1, id)
        ps.executeUpdate()
    }
    return row
}

fun Route.filesRoutes(dataDir: Path) {
    val baseDir = dataDir
    val uploadDir = dataDir.resolve("upload")
    val index = FileIndex(dataDir)

    route("/files") {
        post("/upload") {
            uploadDir.createDirectories()

            val multipart = call.receiveMultipart()
            val uploaded = mutableListOf<UploadedFileDto>()

            val dbPath = dbPathOrNull(dataDir)
            val canUseManaged = dbPath != null

            while (true) {
                val part = multipart.readPart() ?: break
                try {
                    if (part !is PartData.FileItem) {
                        continue
                    }

                    val originalName = part.originalFileName?.trim().takeUnless { it.isNullOrEmpty() } ?: "file"
                    val mime = part.contentType?.toString() ?: ContentType.Application.OctetStream.toString()
                    val storedName = "${UUID.randomUUID()}_${sanitizeFileName(originalName)}"
                    val target = uploadDir.resolve(storedName)

                    val channel = part.provider()
                    var total = 0L
                    target.parent.createDirectories()
                    Files.newOutputStream(target).use { out ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = channel.readAvailable(buffer, 0, buffer.size)
                            if (read <= 0) break
                            total += read
                            if (total > MAX_UPLOAD_FILE_SIZE_BYTES) {
                                throw IllegalArgumentException("file exceeds max size")
                            }
                            out.write(buffer, 0, read)
                        }
                    }

                    if (total == 0L) {
                        runCatching { Files.deleteIfExists(target) }
                        throw IllegalArgumentException("Uploaded file is empty")
                    }

                    val id = if (canUseManaged) {
                        withSqliteConnection(dbPath) { conn ->
                            if (!hasManagedFilesTable(conn)) {
                                throw IllegalArgumentException("managed_files table missing")
                            }
                            val now = System.currentTimeMillis()
                            insertManagedFile(
                                conn = conn,
                                folder = "upload",
                                relativePath = "upload/$storedName",
                                displayName = originalName,
                                mimeType = mime,
                                sizeBytes = total,
                                now = now,
                            )
                        }
                    } else {
                        val allocated = index.allocateId()
                        index.put(
                            FileIndexEntry(
                                id = allocated,
                                storedName = storedName,
                                fileName = originalName,
                                mime = mime,
                            )
                        )
                        allocated
                    }

                    uploaded.add(
                        UploadedFileDto(
                            id = id,
                            fileName = originalName,
                            mime = mime,
                            url = "/api/files/id/$id",
                        )
                    )
                } catch (t: Throwable) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(t.message ?: "upload failed", 400))
                    return@post
                } finally {
                    part.dispose()
                }
            }

            if (uploaded.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("No files uploaded", 400))
                return@post
            }
            call.respond(HttpStatusCode.Created, UploadFilesResponseDto(files = uploaded))
        }

        delete("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid id", 400))
                return@delete
            }

            val dbPath = dbPathOrNull(dataDir)
            if (dbPath != null) {
                val removed = withSqliteConnection(dbPath) { conn ->
                    if (!hasManagedFilesTable(conn)) return@withSqliteConnection null
                    deleteManagedFileById(conn, id)
                }
                if (removed != null) {
                    val file = baseDir.resolve(removed.relativePath).toFile().canonicalFile
                    val base = baseDir.toFile().canonicalFile
                    if (file.path.startsWith(base.path)) {
                        runCatching { Files.deleteIfExists(file.toPath()) }
                    }
                    call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
                    return@delete
                }
            }

            val entry = index.remove(id)
            if (entry != null) {
                runCatching { Files.deleteIfExists(uploadDir.resolve(entry.storedName)) }
                call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("file not found", 404))
            }
        }

        get("/id/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid id", 400))
                return@get
            }

            val dbPath = dbPathOrNull(dataDir)
            if (dbPath != null) {
                val row = withSqliteConnection(dbPath) { conn ->
                    if (!hasManagedFilesTable(conn)) return@withSqliteConnection null
                    getManagedFileById(conn, id)
                }
                if (row != null) {
                    val relativePath = row.relativePath
                    if (relativePath.contains("..") || relativePath.startsWith("/")) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid path", 400))
                        return@get
                    }
                    val target = baseDir.resolve(relativePath)
                    val base = baseDir.toFile().canonicalFile
                    val file = target.toFile().canonicalFile
                    if (!file.path.startsWith(base.path)) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("path traversal", 400))
                        return@get
                    }
                    if (!file.exists() || file.isDirectory) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("file not found", 404))
                        return@get
                    }
                    call.response.headers.append(HttpHeaders.ContentType, row.mimeType)
                    call.respondFile(file)
                    return@get
                }
            }

            val entry = index.get(id)
            if (entry == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("file not found", 404))
                return@get
            }
            val file = uploadDir.resolve(entry.storedName)
            if (!Files.exists(file)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("file missing", 404))
                return@get
            }
            call.response.headers.append(HttpHeaders.ContentType, entry.mime)
            call.respondFile(file.toFile())
        }

        get("/path/{path...}") {
            val segments = call.parameters.getAll("path") ?: emptyList()
            val relative = segments.joinToString("/")
            if (relative.isBlank() || relative.contains("..") || relative.startsWith("/")) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid path", 400))
                return@get
            }

            if (!relative.startsWith("upload/")) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid path", 400))
                return@get
            }

            val target = baseDir.resolve(relative)
            val canonicalBase = baseDir.toFile().canonicalFile
            val canonicalTarget = target.toFile().canonicalFile
            if (!canonicalTarget.path.startsWith(canonicalBase.path)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("path traversal", 400))
                return@get
            }
            if (!canonicalTarget.exists() || canonicalTarget.isDirectory) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("file not found", 404))
                return@get
            }
            call.respondFile(canonicalTarget)
        }
    }
}

private fun sanitizeFileName(name: String): String {
    return name
        .replace("\\u0000", "")
        .replace("/", "_")
        .replace("\\", "_")
        .take(128)
        .ifBlank { "file" }
}

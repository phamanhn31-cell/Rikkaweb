package me.rerere.rikkahub.standalone

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.default
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.request.receive
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.cio.CIO
import io.ktor.server.sse.SSE
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.standalone.backup.BackupImportOptions
import me.rerere.rikkahub.standalone.backup.BackupZipImporter
import me.rerere.rikkahub.standalone.api.SettingsFileStore
import me.rerere.rikkahub.standalone.api.aiIconRoutes
import me.rerere.rikkahub.standalone.api.backupRoutes
import me.rerere.rikkahub.standalone.api.conversationRoutes
import me.rerere.rikkahub.standalone.api.filesRoutes
import me.rerere.rikkahub.standalone.api.settingsRoutes
import me.rerere.rikkahub.standalone.ai.GenerationManager
import java.security.MessageDigest
import java.util.Date
import java.util.UUID
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

private const val DEFAULT_HOST = "0.0.0.0"
private const val DEFAULT_PORT = 8080
private const val DEFAULT_JWT_ENABLED = false

private const val WEB_JWT_ISSUER = "rikkahub-web"
private const val WEB_JWT_AUDIENCE = "rikkahub-web-client"
private const val WEB_JWT_SUBJECT = "web-access"
private const val WEB_JWT_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000
private const val WEB_ACCESS_TOKEN_QUERY_KEY = "access_token"
private const val WEB_AUTH_REALM = "rikkahub-web-api"

@Serializable
private data class HealthResponse(
    val status: String,
    val service: String,
    val dataDir: String,
)

@Serializable
private data class ErrorResponse(
    val error: String,
    val code: Int,
)

@Serializable
private data class WebAuthTokenRequest(
    val password: String,
)

@Serializable
private data class WebAuthTokenResponse(
    val token: String,
    val expiresAt: Long,
)

data class ServerConfig(
    val host: String,
    val port: Int,
    val dataDir: Path,
    val jwtEnabled: Boolean,
    val accessPassword: String,
)

fun main(args: Array<String>) {
    val parsed = Args.parse(args)
    when (parsed) {
        is Args.ParseResult.Help -> {
            println(Args.usage())
            return
        }

        is Args.ParseResult.Import -> {
            val config = parsed.config
            ensureDataDir(config.dataDir)
            val result = BackupZipImporter.importZip(
                zipFile = parsed.zipFile,
                dataDir = config.dataDir,
                options = BackupImportOptions(overwriteExisting = parsed.overwrite),
            )
            println("IMPORT_OK")
            println("settings=${result.importedSettings}")
            println("db=${result.importedDatabaseFiles.joinToString(",")}")
            println("uploadFiles=${result.importedUploadFiles}")
            println("skippedEntries=${result.skippedEntries}")
            if (result.warnings.isNotEmpty()) {
                println("warnings=${result.warnings.joinToString(";")}")
            }
            return
        }

        is Args.ParseResult.Error -> {
            System.err.println(parsed.message)
            System.err.println()
            System.err.println(Args.usage())
            kotlin.system.exitProcess(2)
        }

        is Args.ParseResult.Config -> {
            val config = parsed.config
            ensureDataDir(config.dataDir)
            val engine = startServer(config)
            engine.start(wait = true)
        }
    }
}

private fun ensureDataDir(dataDir: Path) {
    if (dataDir.exists() && !dataDir.isDirectory()) {
        throw IllegalArgumentException("data-dir must be a directory: $dataDir")
    }

    dataDir.createDirectories()
    dataDir.resolve("db").createDirectories()
    dataDir.resolve("upload").createDirectories()
    dataDir.resolve("tmp").createDirectories()
}

fun startServer(config: ServerConfig): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
    return embeddedServer(CIO, host = config.host, port = config.port) {
        configureServer(config)
    }
}

private fun Application.configureServer(config: ServerConfig) {
    val settingsStore = SettingsFileStore(config.dataDir)
    val generationManager = GenerationManager(config.dataDir)

    install(DefaultHeaders)
    install(Compression)
    install(CORS) {
        anyHost()
        allowNonSimpleContentTypes = true
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        anyMethod()
    }
    install(SSE)
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                explicitNulls = false
            },
            contentType = ContentType.Application.Json,
        )
    }

    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorResponse("Not Found", status.value))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Bad Request", 400))
        }
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(cause.message ?: "Internal server error", 500)
            )
        }
    }

    if (config.jwtEnabled) {
        install(Authentication) {
            jwt("auth-jwt") {
                realm = WEB_AUTH_REALM
                verifier { _ ->
                    val secret = config.accessPassword.ifBlank {
                        "__missing_password_${UUID.randomUUID()}__"
                    }
                    buildWebJwtVerifier(secret)
                }
                authHeader { call ->
                    extractAccessToken(
                        authorizationHeader = call.request.headers[HttpHeaders.Authorization],
                        queryToken = call.request.queryParameters[WEB_ACCESS_TOKEN_QUERY_KEY]
                    )?.let { token ->
                        HttpAuthHeader.Single("Bearer", token)
                    }
                }
                validate { credential ->
                    if (config.accessPassword.isBlank()) {
                        null
                    } else {
                        credential.payload.subject?.takeIf { it == WEB_JWT_SUBJECT }?.let {
                            io.ktor.server.auth.jwt.JWTPrincipal(credential.payload)
                        }
                    }
                }
                challenge { _, _ ->
                    if (config.accessPassword.isBlank()) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse("Access password is not configured", HttpStatusCode.Forbidden.value)
                        )
                    } else {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            ErrorResponse("Unauthorized", HttpStatusCode.Unauthorized.value)
                        )
                    }
                }
            }
        }
    }

    routing {
        get("/health") {
            call.respond(
                HealthResponse(
                    status = "ok",
                    service = "rikkahub-standalone",
                    dataDir = config.dataDir.toString(),
                )
            )
        }

        route("/api") {
            aiIconRoutes()

            post("/auth/token") {
                if (!config.jwtEnabled) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("JWT auth is disabled", 400))
                    return@post
                }
                if (config.accessPassword.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Access password is not configured", 400))
                    return@post
                }

                val request = call.receive<WebAuthTokenRequest>()
                if (!secureEquals(request.password, config.accessPassword)) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid password", 401))
                    return@post
                }

                val (token, expiresAt) = createWebJwt(config.accessPassword)
                call.respond(HttpStatusCode.OK, WebAuthTokenResponse(token = token, expiresAt = expiresAt))
            }

            if (config.jwtEnabled) {
                authenticate("auth-jwt") {
                    get("/whoami") {
                        call.respond(mapOf("status" to "ok"))
                    }

                    settingsRoutes(settingsStore)
                    conversationRoutes(config.dataDir, generationManager)
                    filesRoutes(config.dataDir)
                    backupRoutes(config.dataDir, enabled = true)
                }
            } else {
                get("/whoami") {
                    call.respond(mapOf("status" to "ok"))
                }

                settingsRoutes(settingsStore)
                conversationRoutes(config.dataDir, generationManager)
                filesRoutes(config.dataDir)
                backupRoutes(config.dataDir, enabled = false)
            }
        }

        staticResources("/", "static") {
            default("index.html")
            singlePageApplication()
        }
    }
}

private fun createWebJwt(secret: String): Pair<String, Long> {
    val now = System.currentTimeMillis()
    val expiresAt = now + WEB_JWT_TTL_MILLIS
    val token = JWT.create()
        .withIssuer(WEB_JWT_ISSUER)
        .withAudience(WEB_JWT_AUDIENCE)
        .withSubject(WEB_JWT_SUBJECT)
        .withIssuedAt(Date(now))
        .withExpiresAt(Date(expiresAt))
        .sign(Algorithm.HMAC256(secret))
    return token to expiresAt
}

private fun buildWebJwtVerifier(secret: String): JWTVerifier {
    return JWT.require(Algorithm.HMAC256(secret))
        .withIssuer(WEB_JWT_ISSUER)
        .withAudience(WEB_JWT_AUDIENCE)
        .withSubject(WEB_JWT_SUBJECT)
        .build()
}

private fun extractBearerToken(authorizationHeader: String?): String? {
    if (authorizationHeader.isNullOrBlank()) return null
    val prefix = "Bearer "
    if (!authorizationHeader.startsWith(prefix, ignoreCase = true)) return null
    return authorizationHeader.substring(prefix.length).trim().takeIf { it.isNotEmpty() }
}

private fun extractAccessToken(authorizationHeader: String?, queryToken: String?): String? {
    return extractBearerToken(authorizationHeader)
        ?: queryToken?.trim()?.takeIf { it.isNotEmpty() }
}

private fun secureEquals(left: String, right: String): Boolean {
    return MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))
}

private object Args {
    sealed class ParseResult {
        data object Help : ParseResult()
        data class Error(val message: String) : ParseResult()
        data class Config(val config: ServerConfig) : ParseResult()
        data class Import(val zipFile: Path, val overwrite: Boolean, val config: ServerConfig) : ParseResult()
    }

    fun usage(): String {
        return buildString {
            appendLine("rikkahub-standalone")
            appendLine()
            appendLine("Usage:")
            appendLine(
                "  standalone-server [--host <host>] [--port <port>] [--data-dir <path>] " +
                    "[--jwt-enabled <true|false>] [--access-password <password>]"
            )
            appendLine()
            appendLine("Options:")
            appendLine("  --host <host>        Bind host (default: $DEFAULT_HOST)")
            appendLine("  --port <port>        Bind port (default: $DEFAULT_PORT)")
            appendLine("  --data-dir <path>    Data directory (default: ./data)")
            appendLine("  --jwt-enabled <b>    Enable JWT auth (default: $DEFAULT_JWT_ENABLED)")
            appendLine("  --access-password <p>  Access password for token signing")
            appendLine("  --import-zip <path>  Import app backup ZIP into data-dir and exit")
            appendLine("  --import-overwrite <b>  Overwrite existing data (default: true)")
            appendLine("  --help, -h           Show this help")
            appendLine()
            appendLine("Environment variables (override defaults):")
            appendLine("  RIKKAHUB_HOST")
            appendLine("  RIKKAHUB_PORT")
            appendLine("  RIKKAHUB_DATA_DIR")
            appendLine("  RIKKAHUB_JWT_ENABLED")
            appendLine("  RIKKAHUB_ACCESS_PASSWORD")
        }
    }

    fun parse(args: Array<String>): ParseResult {
        if (args.any { it == "--help" || it == "-h" }) {
            return ParseResult.Help
        }

        val envHost = System.getenv("RIKKAHUB_HOST")?.takeIf { it.isNotBlank() }
        val envPort = System.getenv("RIKKAHUB_PORT")?.toIntOrNull()
        val envDataDir = System.getenv("RIKKAHUB_DATA_DIR")?.takeIf { it.isNotBlank() }
        val envJwtEnabled = System.getenv("RIKKAHUB_JWT_ENABLED")?.trim()?.lowercase()
        val envAccessPassword = System.getenv("RIKKAHUB_ACCESS_PASSWORD")

        var host = envHost ?: DEFAULT_HOST
        var port = envPort ?: DEFAULT_PORT
        var dataDir = Path(envDataDir ?: "./data")
        var jwtEnabled = envJwtEnabled?.let { it == "1" || it == "true" || it == "yes" } ?: DEFAULT_JWT_ENABLED
        var accessPassword = envAccessPassword ?: ""
        var importZip: Path? = null
        var importOverwrite = true

        fun requireValue(i: Int, flag: String): String {
            return args.getOrNull(i + 1) ?: throw IllegalArgumentException("Missing value for $flag")
        }

        return try {
            var i = 0
            while (i < args.size) {
                when (val a = args[i]) {
                    "--host" -> {
                        host = requireValue(i, a)
                        i += 2
                    }

                    "--port" -> {
                        val value = requireValue(i, a)
                        port = value.toIntOrNull() ?: throw IllegalArgumentException("Invalid --port: $value")
                        i += 2
                    }

                    "--data-dir" -> {
                        dataDir = Path(requireValue(i, a))
                        i += 2
                    }

                    "--jwt-enabled" -> {
                        val value = requireValue(i, a).trim().lowercase()
                        jwtEnabled = when (value) {
                            "1", "true", "yes" -> true
                            "0", "false", "no" -> false
                            else -> throw IllegalArgumentException("Invalid --jwt-enabled: $value")
                        }
                        i += 2
                    }

                    "--access-password" -> {
                        accessPassword = requireValue(i, a)
                        i += 2
                    }

                    "--import-zip" -> {
                        importZip = Path(requireValue(i, a))
                        i += 2
                    }

                    "--import-overwrite" -> {
                        val value = requireValue(i, a).trim().lowercase()
                        importOverwrite = when (value) {
                            "1", "true", "yes" -> true
                            "0", "false", "no" -> false
                            else -> throw IllegalArgumentException("Invalid --import-overwrite: $value")
                        }
                        i += 2
                    }

                    else -> {
                        if (a.startsWith("--")) {
                            throw IllegalArgumentException("Unknown option: $a")
                        }
                        i += 1
                    }
                }
            }

            if (port !in 1..65535) {
                throw IllegalArgumentException("--port must be between 1 and 65535")
            }

            val config = ServerConfig(
                host = host,
                port = port,
                dataDir = dataDir,
                jwtEnabled = jwtEnabled,
                accessPassword = accessPassword,
            )

            importZip?.let { zip ->
                return ParseResult.Import(zipFile = zip, overwrite = importOverwrite, config = config)
            }

            ParseResult.Config(config)
        } catch (t: Throwable) {
            ParseResult.Error(t.message ?: t::class.simpleName.orEmpty())
        }
    }
}

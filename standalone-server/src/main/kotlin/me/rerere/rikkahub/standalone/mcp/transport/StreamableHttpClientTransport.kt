package me.rerere.rikkahub.standalone.mcp.transport

import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.ClientSSESession
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.sse.sseSession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration

private const val MCP_SESSION_ID_HEADER = "mcp-session-id"
private const val MCP_PROTOCOL_VERSION_HEADER = "mcp-protocol-version"
private const val MCP_RESUMPTION_TOKEN_HEADER = "Last-Event-ID"

class StreamableHttpError(val code: Int? = null, message: String? = null) :
    Exception("Streamable HTTP error: $message")

@OptIn(ExperimentalAtomicApi::class)
class StreamableHttpClientTransport(
    private val client: HttpClient,
    private val url: String,
    private val reconnectionTime: Duration? = null,
    private val requestBuilder: HttpRequestBuilder.() -> Unit = {},
) : AbstractTransport() {
    var sessionId: String? = null
        private set
    var protocolVersion: String? = null

    private val initialized: AtomicBoolean = AtomicBoolean(false)
    private var sseSession: ClientSSESession? = null
    private var sseJob: Job? = null
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    private var lastEventId: String? = null

    override suspend fun start() {
        if (!initialized.compareAndSet(expectedValue = false, newValue = true)) {
            error("StreamableHttpClientTransport already started")
        }
    }

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        send(message, options?.resumptionToken, options?.onResumptionToken)
    }

    suspend fun send(
        message: JSONRPCMessage,
        resumptionToken: String?,
        onResumptionToken: ((String) -> Unit)? = null,
    ) {
        check(initialized.load()) { "Transport is not started" }

        resumptionToken?.let { token ->
            startSseSession(
                resumptionToken = token,
                onResumptionToken = onResumptionToken,
                replayMessageId = if (message is JSONRPCRequest) message.id else null,
            )
            return
        }

        val jsonBody = McpJson.encodeToString(message)
        val response = client.post(url) {
            applyCommonHeaders(this)
            headers.append(HttpHeaders.Accept, "${ContentType.Application.Json}, ${ContentType.Text.EventStream}")
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
            requestBuilder()
        }

        response.headers[MCP_SESSION_ID_HEADER]?.let { sessionId = it }
        response.headers[MCP_PROTOCOL_VERSION_HEADER]?.let { protocolVersion = it }

        if (response.status == HttpStatusCode.Accepted) {
            if (message is JSONRPCNotification && message.method == "notifications/initialized") {
                scope.launch {
                    runCatching { startSseSession(onResumptionToken = onResumptionToken) }
                        .onFailure { _onError(it) }
                }
            }
            return
        }

        if (!response.status.isSuccess()) {
            val error = StreamableHttpError(response.status.value, response.bodyAsText())
            _onError(error)
            throw error
        }

        when (response.contentType()?.withoutParameters()) {
            ContentType.Application.Json -> response.bodyAsText().takeIf { it.isNotEmpty() }?.let { json ->
                runCatching { McpJson.decodeFromString<JSONRPCMessage>(json) }
                    .onSuccess { _onMessage(it) }
                    .onFailure {
                        _onError(it)
                        throw it
                    }
            }

            ContentType.Text.EventStream -> handleInlineSse(
                response,
                onResumptionToken = onResumptionToken,
                replayMessageId = if (message is JSONRPCRequest) message.id else null,
            )

            else -> {
                val body = response.bodyAsText()
                if (response.contentType() == null && body.isBlank()) return
                val ct = response.contentType()?.toString() ?: "<none>"
                val error = StreamableHttpError(-1, "Unexpected content type: $ct")
                _onError(error)
                throw error
            }
        }
    }

    override suspend fun close() {
        if (!initialized.load()) return
        try {
            terminateSession()
            sseSession?.cancel()
            sseJob?.cancelAndJoin()
            scope.cancel()
        } catch (_: Exception) {
        } finally {
            initialized.store(false)
            _onClose()
        }
    }

    suspend fun terminateSession() {
        if (sessionId == null) return
        val response = client.delete(url) {
            applyCommonHeaders(this)
            requestBuilder()
        }
        if (!response.status.isSuccess() && response.status != HttpStatusCode.MethodNotAllowed) {
            val error = StreamableHttpError(
                response.status.value,
                "Failed to terminate session: ${response.status.description}",
            )
            _onError(error)
            throw error
        }

        sessionId = null
        lastEventId = null
    }

    private suspend fun startSseSession(
        resumptionToken: String? = null,
        replayMessageId: RequestId? = null,
        onResumptionToken: ((String) -> Unit)? = null,
    ) {
        sseSession?.cancel()
        sseJob?.cancelAndJoin()

        try {
            sseSession = client.sseSession(
                urlString = url,
                reconnectionTime = reconnectionTime,
            ) {
                method = HttpMethod.Get
                applyCommonHeaders(this)
                accept(ContentType.Application.Json)
                (resumptionToken ?: lastEventId)?.let { headers.append(MCP_RESUMPTION_TOKEN_HEADER, it) }
                requestBuilder()
            }
        } catch (e: SSEClientException) {
            val responseStatus = e.response?.status
            val responseContentType = e.response?.contentType()

            if (responseStatus == HttpStatusCode.MethodNotAllowed) return
            if (responseContentType?.match(ContentType.Application.Json) == true) return
            throw e
        }

        val session = sseSession ?: return
        val newSessionId = session.call.response.headers[MCP_SESSION_ID_HEADER]
        newSessionId?.let { sessionId = it }
        session.call.response.headers[MCP_PROTOCOL_VERSION_HEADER]?.let { protocolVersion = it }
        replayMessageId?.let { onResumptionToken?.invoke(it.toString()) }

        sseJob = scope.launch(CoroutineName("StreamableHttpClientTransport.sse#${hashCode()}")) {
            try {
                session.incoming.collect { event ->
                    val id = event.id
                    if (!id.isNullOrBlank()) {
                        lastEventId = id
                        onResumptionToken?.invoke(id)
                    }
                    event.data?.takeIf { it.isNotBlank() }?.let { data ->
                        runCatching { McpJson.decodeFromString<JSONRPCMessage>(data) }
                            .onSuccess { _onMessage(it) }
                            .onFailure { _onError(it) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _onError(e)
            }
        }
    }

    private suspend fun handleInlineSse(
        response: HttpResponse,
        onResumptionToken: ((String) -> Unit)?,
        replayMessageId: RequestId?,
    ) {
        val channel = response.bodyAsChannel()
        val buffer = StringBuilder()
        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            if (line.isBlank()) {
                val msg = parseInlineSseEvent(buffer.toString())
                buffer.clear()
                msg?.let {
                    if (it.id != null) {
                        lastEventId = it.id
                        onResumptionToken?.invoke(it.id)
                    }
                    if (it.data.isNotBlank()) {
                        runCatching { McpJson.decodeFromString<JSONRPCMessage>(it.data) }
                            .onSuccess { _onMessage(it) }
                            .onFailure { _onError(it) }
                    }
                }
                continue
            }
            buffer.append(line).append("\n")
        }
        replayMessageId?.let { onResumptionToken?.invoke(it.toString()) }
    }

    private data class InlineSseEvent(val id: String?, val data: String)

    private fun parseInlineSseEvent(raw: String): InlineSseEvent? {
        if (raw.isBlank()) return null
        var id: String? = null
        val dataLines = mutableListOf<String>()
        raw.lineSequence().forEach { line ->
            when {
                line.startsWith("id:") -> id = line.removePrefix("id:").trim()
                line.startsWith("data:") -> dataLines.add(line.removePrefix("data:").trim())
            }
        }
        return InlineSseEvent(id = id, data = dataLines.joinToString("\n"))
    }

    private fun applyCommonHeaders(builder: HttpRequestBuilder) {
        builder.headers {
            sessionId?.let { append(MCP_SESSION_ID_HEADER, it) }
            protocolVersion?.let { append(MCP_PROTOCOL_VERSION_HEADER, it) }
        }
    }
}

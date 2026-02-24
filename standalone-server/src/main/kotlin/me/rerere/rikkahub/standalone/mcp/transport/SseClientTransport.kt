package me.rerere.rikkahub.standalone.mcp.transport

import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.ClientSSESession
import io.ktor.client.plugins.sse.sseSession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.append
import io.ktor.http.isSuccess
import io.ktor.http.protocolWithAuthority
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration

@OptIn(ExperimentalAtomicApi::class)
class SseClientTransport(
    private val client: HttpClient,
    private val urlString: String?,
    private val reconnectionTime: Duration? = null,
    private val requestBuilder: HttpRequestBuilder.() -> Unit = {},
) : AbstractTransport() {

    private val initialized: AtomicBoolean = AtomicBoolean(false)
    private val endpoint = CompletableDeferred<String>()

    private lateinit var session: ClientSSESession
    private lateinit var scope: CoroutineScope
    private var job: Job? = null

    private val baseUrl: String by lazy {
        session.call.request.url.let { url ->
            val path = url.encodedPath
            when {
                path.isEmpty() -> url.protocolWithAuthority
                path.endsWith("/") -> url.protocolWithAuthority + path.removeSuffix("/")
                else -> url.protocolWithAuthority + path.take(path.lastIndexOf("/"))
            }
        }
    }

    override suspend fun start() {
        check(initialized.compareAndSet(expectedValue = false, newValue = true)) {
            "SseClientTransport already started"
        }

        try {
            session = urlString?.let {
                client.sseSession(
                    urlString = it,
                    reconnectionTime = reconnectionTime,
                    block = requestBuilder,
                )
            } ?: client.sseSession(
                reconnectionTime = reconnectionTime,
                block = requestBuilder,
            )
            scope = CoroutineScope(session.coroutineContext + SupervisorJob())

            job = scope.launch(CoroutineName("SseClientTransport.collect#${hashCode()}")) {
                collectMessages()
            }

            endpoint.await()
        } catch (e: Exception) {
            closeResources()
            initialized.store(false)
            throw e
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
        check(initialized.load()) { "SseClientTransport is not initialized" }
        check(job?.isActive == true) { "SseClientTransport is closed" }
        check(endpoint.isCompleted) { "Not connected" }

        try {
            val response = client.post(endpoint.getCompleted()) {
                requestBuilder()
                headers.append(HttpHeaders.ContentType, ContentType.Application.Json)
                setBody(McpJson.encodeToString(message))
            }

            val bodyText = response.bodyAsText()
            if (!response.status.isSuccess()) {
                error("Error POSTing to endpoint (HTTP ${response.status}): $bodyText")
            }
        } catch (e: Throwable) {
            _onError(e)
            throw e
        }
    }

    override suspend fun close() {
        if (!initialized.load()) return
        closeResources()
    }

    private suspend fun CoroutineScope.collectMessages() {
        try {
            session.incoming.collect { event ->
                ensureActive()

                when (event.event) {
                    "error" -> {
                        val error = IllegalStateException("SSE error: ${event.data}")
                        _onError(error)
                        throw error
                    }

                    "open" -> Unit
                    "endpoint" -> handleEndpoint(event.data.orEmpty())
                    else -> handleMessage(event.data.orEmpty())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _onError(e)
            throw e
        } finally {
            closeResources()
        }
    }

    private fun handleEndpoint(eventData: String) {
        try {
            val endpointUrl = if (eventData.startsWith("/")) {
                Url(session.call.request.url.protocolWithAuthority + eventData)
            } else {
                Url("$baseUrl/$eventData")
            }
            endpoint.complete(endpointUrl.toString())
        } catch (e: Throwable) {
            _onError(e)
            endpoint.completeExceptionally(e)
            throw e
        }
    }

    private suspend fun handleMessage(data: String) {
        try {
            val message = McpJson.decodeFromString<JSONRPCMessage>(data)
            _onMessage(message)
        } catch (e: SerializationException) {
            _onError(e)
        }
    }

    private suspend fun closeResources() {
        if (!initialized.compareAndSet(expectedValue = true, newValue = false)) return
        job?.cancelAndJoin()
        try {
            if (::session.isInitialized) session.cancel()
            if (::scope.isInitialized) scope.cancel()
            endpoint.cancel()
        } catch (e: Throwable) {
            _onError(e)
        }
        _onClose()
    }
}

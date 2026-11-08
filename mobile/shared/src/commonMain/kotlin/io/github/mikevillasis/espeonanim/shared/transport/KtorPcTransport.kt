package io.github.mikevillasis.espeonanim.shared.transport

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.onUpload
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [PcTransport] over HTTP with Ktor. The engine comes from the platform (OkHttp on Android,
 * Darwin on iOS) unless a client is passed in (tests).
 */
class KtorPcTransport(
    private val pairing: PairingInfo,
    private val client: HttpClient = defaultClient(),
    private val pollIntervalMs: Long = 1_000,
    private val maxPollIntervalMs: Long = 5_000,
    /** Consecutive failed polls tolerated before giving up (about 1 minute with the defaults). */
    private val maxPollFailures: Int = 15,
) : PcTransport {

    private val baseUrl = pairing.baseUrl

    override suspend fun ping(): PcInfo = call {
        val r = client.get("$baseUrl/api/v1/ping") {
            timeout {
                requestTimeoutMillis = PING_TIMEOUT_MS
                connectTimeoutMillis = PING_TIMEOUT_MS
                socketTimeoutMillis = PING_TIMEOUT_MS
            }
        }
        checkOk(r)
        val info: PcInfo = r.body()
        if (info.app != "EspeonAnim") throw PcTransportException.Server(r.status.value, "That is not an EspeonAnim receiver.")
        info
    }

    override suspend fun upload(packageFile: UploadSource, onProgress: (Float) -> Unit): String = call {
        val content = object : OutgoingContent.ReadChannelContent() {
            override val contentLength: Long = packageFile.length
            override val contentType: ContentType = ContentType.Application.Zip
            override fun readFrom(): ByteReadChannel = packageFile.open()
        }
        val r = client.post("$baseUrl/api/v1/packages") {
            header(HttpHeaders.Authorization, "Bearer ${pairing.token}")
            timeout {
                // A large file on slow Wi-Fi can take minutes; only stalls (no bytes for a while) time out.
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = UPLOAD_SOCKET_TIMEOUT_MS
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
            }
            setBody(content)
            onUpload { sent, total ->
                val t = total ?: packageFile.length
                if (t > 0) onProgress((sent.toDouble() / t).toFloat().coerceIn(0f, 1f))
            }
        }
        checkOk(r)
        val id = json.parseToJsonElement(r.bodyAsText()).jsonObject["id"]?.jsonPrimitive?.content
        if (id.isNullOrBlank()) throw PcTransportException.Server(r.status.value, "The PC did not return a job id.")
        onProgress(1f)
        id
    }

    override suspend fun status(id: String): JobStatus = call {
        val r = client.get("$baseUrl/api/v1/packages/$id") {
            header(HttpHeaders.Authorization, "Bearer ${pairing.token}")
            timeout {
                requestTimeoutMillis = STATUS_TIMEOUT_MS
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = STATUS_TIMEOUT_MS
            }
        }
        checkOk(r)
        val dto = try {
            json.decodeFromString(JobStatusDto.serializer(), r.bodyAsText())
        } catch (e: kotlinx.serialization.SerializationException) {
            throw PcTransportException.Server(r.status.value, "Unreadable status reply.")
        }
        JobStatus(
            id = dto.id.ifEmpty { id },
            state = JobState.parse(dto.state),
            progress = dto.progress.toFloat().coerceIn(0f, 1f),
            message = dto.message,
            quality = dto.quality,
            warnings = dto.warnings,
        )
    }

    override suspend fun awaitResult(id: String, onUpdate: (JobStatus) -> Unit): JobStatus {
        var failures = 0
        var wait = pollIntervalMs
        while (true) {
            try {
                val s = status(id)
                failures = 0
                wait = pollIntervalMs
                onUpdate(s)
                if (s.state.isTerminal) return s
            } catch (e: PcTransportException.Unreachable) {
                if (++failures >= maxPollFailures) throw e
                wait = (wait * 3 / 2).coerceAtMost(maxPollIntervalMs)
            } catch (e: PcTransportException.Server) {
                // 404 = the PC forgot the job (Unreal restarted); 5xx can be transient.
                if (e.httpStatus == 404 || ++failures >= maxPollFailures) throw e
                wait = (wait * 3 / 2).coerceAtMost(maxPollIntervalMs)
            }
            delay(wait)
        }
    }

    // ---- helpers ----

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: PcTransportException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // Connect/read/timeouts and dropped sockets all end up here, with platform-specific types.
        throw PcTransportException.Unreachable(e)
    }

    private suspend fun checkOk(r: HttpResponse) {
        if (r.status.isSuccess()) return
        if (r.status.value == 401) throw PcTransportException.Unauthorized()
        val detail = try {
            (json.parseToJsonElement(r.bodyAsText()) as? JsonObject)?.get("error")?.jsonPrimitive?.content.orEmpty()
        } catch (_: Exception) {
            ""
        }
        throw PcTransportException.Server(r.status.value, detail)
    }

    companion object {
        private const val PING_TIMEOUT_MS = 4_000L
        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val STATUS_TIMEOUT_MS = 10_000L
        private const val UPLOAD_SOCKET_TIMEOUT_MS = 60_000L

        internal val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun defaultClient(): HttpClient = HttpClient {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = STATUS_TIMEOUT_MS
                requestTimeoutMillis = STATUS_TIMEOUT_MS
            }
            expectSuccess = false
        }
    }
}

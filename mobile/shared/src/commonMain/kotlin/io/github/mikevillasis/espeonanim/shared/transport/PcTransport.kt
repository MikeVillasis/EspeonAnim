package io.github.mikevillasis.espeonanim.shared.transport

import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A file that can be streamed to the PC without loading it into memory. */
interface UploadSource {
    /** Size in bytes (sent as Content-Length). */
    val length: Long

    /** Opens a fresh stream over the file; called once per upload attempt. */
    fun open(): ByteReadChannel
}

/** `GET /api/v1/ping` response. */
@Serializable
data class PcInfo(
    val name: String,
    val app: String = "EspeonAnim",
    val version: String = "",
    val api: Int = 1,
)

enum class JobState {
    Received, Processing, Importing, Done, Failed, Unknown;

    val isTerminal: Boolean get() = this == Done || this == Failed

    companion object {
        fun parse(s: String): JobState = when (s) {
            "received" -> Received
            "processing" -> Processing
            "importing" -> Importing
            "done" -> Done
            "failed" -> Failed
            else -> Unknown
        }
    }
}

@Serializable
data class JobWarning(
    val code: String = "",
    val message: String = "",
    val suggestion: String = "",
)

/** Raw wire format of `GET /api/v1/packages/<id>`; use [JobStatus]. */
@Serializable
internal data class JobStatusDto(
    val id: String = "",
    val state: String = "",
    val progress: Double = 0.0,
    val message: String = "",
    val quality: String = "",
    val warnings: List<JobWarning> = emptyList(),
    @SerialName("error") val error: String? = null,
)

/** Where a job on the PC stands. [progress] is 0..1 over the whole job. */
data class JobStatus(
    val id: String,
    val state: JobState,
    val progress: Float,
    val message: String,
    /** "good", "usable" or "poor" once the job is done, otherwise empty. */
    val quality: String,
    val warnings: List<JobWarning>,
) {
    val isDone: Boolean get() = state == JobState.Done
    val isFailed: Boolean get() = state == JobState.Failed
}

/** Why talking to the PC failed. [message] is plain language, ready to show to the user. */
sealed class PcTransportException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Could not connect, or the connection dropped. */
    class Unreachable(cause: Throwable? = null) : PcTransportException(
        "Can't reach your PC. Make sure the phone is on the same Wi-Fi network as the PC, " +
            "that Unreal Editor is open, and that the firewall allows EspeonAnim.",
        cause,
    )

    /** The PC rejected the pairing token (401). */
    class Unauthorized : PcTransportException(
        "The PC did not accept this pairing. Scan the QR code in Unreal again " +
            "(it changes when you click \"New pairing code\").",
    )

    /** The PC answered with an error (4xx other than 401, 5xx) or an unreadable reply. */
    class Server(val httpStatus: Int, detail: String) : PcTransportException(
        if (detail.isNotBlank()) "The PC reported a problem: $detail" else "The PC reported a problem (HTTP $httpStatus).",
    )
}

/** Sends a finished `.espeon` package to the PC (docs/formats.md section 5). */
interface PcTransport {
    /** Checks that the PC is there and returns its name. No token is sent. */
    suspend fun ping(): PcInfo

    /** Streams [packageFile] to the PC; [onProgress] gets 0..1. Returns the job id. */
    suspend fun upload(packageFile: UploadSource, onProgress: (Float) -> Unit = {}): String

    suspend fun status(id: String): JobStatus

    /**
     * Polls [status] (every second, slowing down after errors) until the job is `done` or `failed`.
     * [onUpdate] sees every status received. Throws [PcTransportException] when the PC stays
     * unreachable or rejects the token; cancel the coroutine to stop.
     */
    suspend fun awaitResult(id: String, onUpdate: (JobStatus) -> Unit = {}): JobStatus
}

package io.github.mikevillasis.espeonanim.shared.transport

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** State of sending the current package to the PC. */
sealed interface SendState {
    data object Idle : SendState
    data class Uploading(val progress: Float) : SendState

    /** Uploaded; the PC is working on it ([status] is null until the first poll answers). */
    data class Processing(val status: JobStatus?) : SendState
    data class Done(val status: JobStatus) : SendState
    data class Failed(val message: String, val needsRescan: Boolean, val status: JobStatus? = null) : SendState
    data object Cancelled : SendState

    val active: Boolean get() = this is Uploading || this is Processing
}

/** Final result of a job, persisted so it can be shown after the process was killed. */
@Serializable
data class PersistedOutcome(
    val done: Boolean,
    val message: String = "",
    val quality: String = "",
    val warnings: List<JobWarning> = emptyList(),
    val needsRescan: Boolean = false,
)

/**
 * The in-flight (or just finished) send, persisted so a restarted app/service resumes polling
 * instead of uploading again. [jobId] is null while the upload has not completed.
 */
@Serializable
data class PersistedJob(
    val pairingUri: String,
    val filePath: String,
    val jobId: String? = null,
    val outcome: PersistedOutcome? = null,
) {
    fun withJobId(id: String) = copy(jobId = id, outcome = null)

    /** Records a terminal [state]; other states leave the record unchanged. */
    fun withOutcome(state: SendState): PersistedJob = when (state) {
        is SendState.Done -> copy(outcome = PersistedOutcome(true, state.status.message, state.status.quality, state.status.warnings))
        is SendState.Failed -> copy(
            outcome = PersistedOutcome(false, state.message, state.status?.quality.orEmpty(), state.status?.warnings.orEmpty(), state.needsRescan),
        )
        else -> this
    }

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** null when [text] is not a valid record. */
        fun decode(text: String): PersistedJob? =
            try { JSON.decodeFromString(serializer(), text) } catch (_: Exception) { null }
    }
}

/** Where [PersistedJob] lives between process lifetimes. */
interface JobStore {
    fun load(): PersistedJob?
    fun save(job: PersistedJob)
    fun clear()
}

class MemoryJobStore(private var job: PersistedJob? = null) : JobStore {
    override fun load() = job
    override fun save(job: PersistedJob) { this.job = job }
    override fun clear() { job = null }
}

/** What a (re)started app or service should do about a persisted job. */
sealed interface ResumeDecision {
    data object Nothing : ResumeDecision

    /** Already uploaded: keep polling [jobId]; never upload again. */
    data class Poll(val jobId: String, val pairing: PairingInfo) : ResumeDecision

    /** Nothing to run; just show [state] (a stored outcome, or an interrupted upload). */
    data class Show(val state: SendState) : ResumeDecision
}

object SendJobLogic {
    fun decide(job: PersistedJob?): ResumeDecision {
        if (job == null) return ResumeDecision.Nothing
        job.outcome?.let { return ResumeDecision.Show(it.toState(job.jobId)) }
        val id = job.jobId
            ?: return ResumeDecision.Show(
                SendState.Failed("The upload was interrupted before it finished. Send it again.", needsRescan = false),
            )
        val pairing = (PairingInfo.parse(job.pairingUri) as? PairingInfo.ParseResult.Ok)?.info
            ?: return ResumeDecision.Show(SendState.Failed("The pairing was lost. Scan the QR code in Unreal again.", needsRescan = true))
        return ResumeDecision.Poll(id, pairing)
    }

    private fun PersistedOutcome.toState(jobId: String?): SendState {
        val status = JobStatus(
            id = jobId.orEmpty(),
            state = if (done) JobState.Done else JobState.Failed,
            progress = 1f, message = message, quality = quality, warnings = warnings,
        )
        return if (done) SendState.Done(status)
        else SendState.Failed(
            message.ifBlank { "The PC could not turn this take into an animation." },
            needsRescan,
            status.takeIf { quality.isNotEmpty() || warnings.isNotEmpty() },
        )
    }
}

/** What the notification should say for a [SendState]. [progress] is 0..100, null = indeterminate. */
data class NotificationContent(
    val title: String,
    val text: String,
    val progress: Int?,
    val ongoing: Boolean,
    val cancelLabel: String?,
)

object SendNotifications {
    fun contentFor(state: SendState, pcName: String): NotificationContent? = when (state) {
        is SendState.Uploading -> {
            val pct = (state.progress * 100).toInt().coerceIn(0, 100)
            NotificationContent("Sending to $pcName", "Uploading $pct%", pct, true, "Cancel")
        }
        is SendState.Processing -> {
            val st = state.status
            val msg = st?.message?.ifBlank { null } ?: "waiting to start"
            val pct = st?.progress?.takeIf { it > 0f }?.let { (it * 100).toInt().coerceIn(0, 100) }
            NotificationContent("Sending to $pcName", "Processing on $pcName: $msg", pct, true, "Stop watching")
        }
        is SendState.Done -> NotificationContent(
            "Animation ready in Unreal", outcomeText(state.status.quality, state.status.warnings, ""), null, false, null,
        )
        is SendState.Failed -> NotificationContent(
            "Sending failed", outcomeText(state.status?.quality.orEmpty(), state.status?.warnings.orEmpty(), state.message), null, false, null,
        )
        SendState.Idle, SendState.Cancelled -> null
    }

    /** Failure [message], quality, and the first warning's suggestion. */
    fun outcomeText(quality: String, warnings: List<JobWarning>, message: String): String {
        val parts = ArrayList<String>()
        if (message.isNotBlank()) parts += message
        if (quality.isNotBlank()) parts += "Quality: $quality"
        warnings.firstOrNull()?.let { w ->
            val tip = w.suggestion.ifBlank { w.message }
            if (tip.isNotBlank()) parts += "Tip: $tip"
        }
        return parts.joinToString(". ").ifBlank { "Done." }
    }
}

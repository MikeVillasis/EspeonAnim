package io.github.mikevillasis.espeonanim.transport

import android.app.NotificationManager
import android.content.Context
import androidx.core.content.ContextCompat
import io.github.mikevillasis.espeonanim.shared.transport.JobStore
import io.github.mikevillasis.espeonanim.shared.transport.ResumeDecision
import io.github.mikevillasis.espeonanim.shared.transport.SendJobLogic
import io.github.mikevillasis.espeonanim.shared.transport.SendState
import io.github.mikevillasis.espeonanim.shared.transport.SharedPrefsJobStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Process-wide send state shared by [SendService] (which does the work) and the UI (which only
 * observes). The job itself is persisted in a [JobStore], so a killed process picks it up again.
 */
object SendRepository {
    private val _state = MutableStateFlow<SendState>(SendState.Idle)
    val state: StateFlow<SendState> = _state.asStateFlow()

    @Volatile private var store: JobStore? = null

    fun store(context: Context): JobStore =
        store ?: synchronized(this) {
            store ?: SharedPrefsJobStore(context.applicationContext).also { store = it }
        }

    internal fun publish(s: SendState) {
        _state.value = s
    }

    /** Starts uploading [file] in the foreground service. No-op while a send is already running. */
    fun start(context: Context, file: File) {
        if (_state.value.active) return
        _state.value = SendState.Uploading(0f)
        try {
            ContextCompat.startForegroundService(context, SendService.startIntent(context, file))
        } catch (e: Exception) {
            _state.value = SendState.Failed("Android would not start the background transfer: ${e.message.orEmpty()}", false)
        }
    }

    /** Cancels the upload, or for an uploaded job just stops watching it. */
    fun cancel(context: Context) {
        context.startService(SendService.cancelIntent(context))
    }

    /** UI dismissed a finished result. */
    fun reset(context: Context) {
        if (_state.value.active) return
        _state.value = SendState.Idle
        store(context).clear()
        context.getSystemService(NotificationManager::class.java)?.cancel(SendService.RESULT_NOTIFICATION_ID)
    }

    /**
     * After the app process was (re)started: show a stored outcome, or restart the service to keep
     * polling an uploaded job. Does nothing when this process already knows about a send.
     */
    fun restoreIfNeeded(context: Context) {
        if (_state.value != SendState.Idle) return
        when (val d = SendJobLogic.decide(store(context).load())) {
            ResumeDecision.Nothing -> Unit
            is ResumeDecision.Show -> _state.value = d.state
            is ResumeDecision.Poll -> {
                _state.value = SendState.Processing(null)
                try {
                    ContextCompat.startForegroundService(context, SendService.resumeIntent(context))
                } catch (e: Exception) {
                    _state.value = SendState.Failed("Android would not resume the background transfer: ${e.message.orEmpty()}", false)
                }
            }
        }
    }
}

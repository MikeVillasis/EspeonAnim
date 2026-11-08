package io.github.mikevillasis.espeonanim.transport

import android.content.Context
import io.github.mikevillasis.espeonanim.shared.transport.FileUploadSource
import io.github.mikevillasis.espeonanim.shared.transport.JobStatus
import io.github.mikevillasis.espeonanim.shared.transport.KtorPcTransport
import io.github.mikevillasis.espeonanim.shared.transport.PairingInfo
import io.github.mikevillasis.espeonanim.shared.transport.PairingStore
import io.github.mikevillasis.espeonanim.shared.transport.PcTransport
import io.github.mikevillasis.espeonanim.shared.transport.PcTransportException
import io.github.mikevillasis.espeonanim.shared.transport.SharedPrefsPairingStore
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** State of the "scan the QR code" flow. */
sealed interface PairUi {
    data object Scanning : PairUi
    data object Checking : PairUi

    /** Paired and the PC answered; [pcName] is what the PC calls itself. */
    data class Paired(val pcName: String) : PairUi
    data class Error(val message: String) : PairUi
}

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

/**
 * Pairing and Wi-Fi sending for the Android app: glue between the shared [PcTransport], the
 * stored pairing and the UI. Lives as long as the owning ViewModel.
 */
class PcLink(
    context: Context,
    private val scope: CoroutineScope,
    private val store: PairingStore = SharedPrefsPairingStore(context),
    private val client: HttpClient = KtorPcTransport.defaultClient(),
    private val transportFor: (PairingInfo) -> PcTransport = { KtorPcTransport(it, client) },
) {
    private val _pairing = MutableStateFlow(store.load())
    val pairing: StateFlow<PairingInfo?> = _pairing.asStateFlow()

    private val _pairUi = MutableStateFlow<PairUi>(PairUi.Scanning)
    val pairUi: StateFlow<PairUi> = _pairUi.asStateFlow()

    /** null = unknown / not checked, true = the paired PC answered, false = it did not. */
    private val _online = MutableStateFlow<Boolean?>(null)
    val online: StateFlow<Boolean?> = _online.asStateFlow()

    private val _send = MutableStateFlow<SendState>(SendState.Idle)
    val send: StateFlow<SendState> = _send.asStateFlow()

    private var sendJob: Job? = null
    private var pairJob: Job? = null

    // ---- Pairing ----

    fun startScanning() {
        pairJob?.cancel()
        _pairUi.value = PairUi.Scanning
    }

    /** Called with every decoded QR text; ignores repeats while a check is running. */
    fun onQrText(text: String) {
        val ui = _pairUi.value
        if (ui is PairUi.Checking || ui is PairUi.Paired) return
        when (val r = PairingInfo.parse(text)) {
            is PairingInfo.ParseResult.Invalid -> _pairUi.value = PairUi.Error(r.reason)
            is PairingInfo.ParseResult.Ok -> {
                _pairUi.value = PairUi.Checking
                pairJob = scope.launch {
                    try {
                        val info = transportFor(r.info).ping()
                        val named = r.info.copy(pcName = info.name.ifBlank { r.info.pcName })
                        store.save(named)
                        _pairing.value = named
                        _online.value = true
                        _pairUi.value = PairUi.Paired(named.pcName)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: PcTransportException) {
                        _pairUi.value = PairUi.Error(e.message.orEmpty())
                    }
                }
            }
        }
    }

    fun forgetPairing() {
        store.clear()
        _pairing.value = null
        _online.value = null
    }

    /** Pings the stored PC so the UI can show whether it is reachable right now. */
    fun refreshOnline() {
        val p = _pairing.value ?: run { _online.value = null; return }
        scope.launch {
            _online.value = try {
                transportFor(p).ping(); true
            } catch (e: CancellationException) {
                throw e
            } catch (e: PcTransportException) {
                false
            }
        }
    }

    // ---- Sending ----

    fun send(file: File) {
        val p = _pairing.value ?: return
        if (_send.value.active) return
        _send.value = SendState.Uploading(0f)
        sendJob = scope.launch {
            val transport = transportFor(p)
            try {
                val id = transport.upload(FileUploadSource(file)) { _send.value = SendState.Uploading(it) }
                _send.value = SendState.Processing(null)
                val result = transport.awaitResult(id) { _send.value = SendState.Processing(it) }
                _send.value = if (result.isDone) SendState.Done(result)
                else SendState.Failed(result.message.ifBlank { "The PC could not turn this take into an animation." }, false, result)
            } catch (e: CancellationException) {
                _send.value = SendState.Cancelled
                throw e
            } catch (e: PcTransportException) {
                _send.value = SendState.Failed(e.message.orEmpty(), needsRescan = e is PcTransportException.Unauthorized)
            }
        }
    }

    fun cancelSend() {
        sendJob?.cancel()
    }

    fun resetSend() {
        if (!_send.value.active) _send.value = SendState.Idle
    }

    fun close() {
        client.close()
    }
}

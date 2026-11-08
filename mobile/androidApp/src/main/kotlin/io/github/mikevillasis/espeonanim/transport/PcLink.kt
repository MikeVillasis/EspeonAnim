package io.github.mikevillasis.espeonanim.transport

import android.content.Context
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

/** State of the "scan the QR code" flow. */
sealed interface PairUi {
    data object Scanning : PairUi
    data object Checking : PairUi

    /** Paired and the PC answered; [pcName] is what the PC calls itself. */
    data class Paired(val pcName: String) : PairUi
    data class Error(val message: String) : PairUi
}

/**
 * Pairing for the Android app (sending lives in [SendService]): glue between the shared [PcTransport], the
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

    fun close() {
        client.close()
    }
}

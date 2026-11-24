package io.github.mikevillasis.espeonanim.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.github.mikevillasis.espeonanim.shared.session.VoiceCommand
import io.github.mikevillasis.espeonanim.shared.session.matchVoiceCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VoiceState { Off, Listening, Unavailable, NeedsPermission }

/**
 * Continuous "Start" / "Stop" listening on top of [SpeechRecognizer] (on-device when possible).
 * Main thread only: [SpeechRecognizer] requires it and all callbacks arrive there.
 *
 * Restart loop: a recognizer session ends by itself after a pause in speech (results or a
 * NO_MATCH / SPEECH_TIMEOUT error), so listening is kept alive by starting a new session each time
 * one ends, after a short backoff so a failing recognizer cannot spin. Commands are taken from
 * partial results for low latency; once one fires, the session is cancelled and restarted so the
 * rest of that utterance cannot fire it again and the next word starts from fresh text.
 */
class VoiceCommandListener(
    context: Context,
    private val onCommand: (VoiceCommand) -> Unit,
) {
    private val ctx = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(VoiceState.Off)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var fired = false
    private var hardErrors = 0
    /** Cleared when the on-device model lacks the language; the regular recognizer is used from then on. */
    private var tryOnDevice = true
    private var usingOnDevice = false
    private val restart = Runnable { startSession() }

    private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
    }

    fun start() {
        if (running) return
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            _state.value = VoiceState.Unavailable
            return
        }
        running = true
        hardErrors = 0
        _state.value = VoiceState.Listening
        startSession()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(restart)
        destroyRecognizer()
        if (_state.value == VoiceState.Listening) _state.value = VoiceState.Off
    }

    private fun createRecognizer(): SpeechRecognizer {
        usingOnDevice = tryOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        return if (usingOnDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        else SpeechRecognizer.createSpeechRecognizer(ctx)
    }

    private fun startSession() {
        if (!running) return
        try {
            val r = recognizer ?: createRecognizer().also { it.setRecognitionListener(listener); recognizer = it }
            fired = false
            r.startListening(intent)
        } catch (t: Throwable) {
            giveUp()
        }
    }

    private fun scheduleRestart(delayMs: Long, recreate: Boolean = false) {
        if (!running) return
        if (recreate) destroyRecognizer()
        handler.removeCallbacks(restart)
        handler.postDelayed(restart, delayMs)
    }

    private fun destroyRecognizer() {
        recognizer?.let {
            it.setRecognitionListener(null)
            it.cancel()
            it.destroy()
        }
        recognizer = null
    }

    private fun giveUp(state: VoiceState = VoiceState.Unavailable) {
        running = false
        handler.removeCallbacks(restart)
        destroyRecognizer()
        _state.value = state
    }

    private fun handleText(results: Bundle?) {
        if (!running || fired) return
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
        val command = matchVoiceCommand(text) ?: return
        fired = true
        onCommand(command)
        recognizer?.cancel() // no onResults/onError follows a cancel, so restart explicitly
        scheduleRestart(300)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() { hardErrors = 0 }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) = handleText(partialResults)

        override fun onResults(results: Bundle?) {
            handleText(results) // a final result can carry a word no partial did
            if (!fired) scheduleRestart(300)
        }

        override fun onError(error: Int) {
            if (!running) return
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> giveUp(VoiceState.NeedsPermission)
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart(300)
                SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> scheduleRestart(1000, recreate = true)
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    if (usingOnDevice) { // on-device English model missing: fall back to the regular recognizer
                        tryOnDevice = false
                        scheduleRestart(300, recreate = true)
                    } else giveUp()
                else -> { // network/server/audio: retry slowly, then report unavailable
                    if (++hardErrors >= MAX_HARD_ERRORS) giveUp() else scheduleRestart(1000, recreate = true)
                }
            }
        }
    }

    private companion object {
        const val MAX_HARD_ERRORS = 5
    }
}

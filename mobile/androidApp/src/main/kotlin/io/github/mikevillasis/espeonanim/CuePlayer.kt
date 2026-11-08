package io.github.mikevillasis.espeonanim

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Audible cues for a performer who cannot see the screen: a short beep per countdown second,
 * a higher and longer tone when recording starts, a two-note tone when it stops.
 */
class CuePlayer {
    private val tones: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 100)
    } catch (_: Throwable) {
        null
    }

    fun countdown() = play(ToneGenerator.TONE_PROP_BEEP, 120)
    fun recordingStarted() = play(ToneGenerator.TONE_DTMF_D, 500)
    fun recordingStopped() = play(ToneGenerator.TONE_PROP_ACK, 400)

    private fun play(tone: Int, ms: Int) {
        try { tones?.startTone(tone, ms) } catch (_: Throwable) { }
    }

    fun release() {
        try { tones?.release() } catch (_: Throwable) { }
    }
}

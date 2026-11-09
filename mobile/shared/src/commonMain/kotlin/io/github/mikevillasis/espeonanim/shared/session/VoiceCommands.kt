package io.github.mikevillasis.espeonanim.shared.session

/** A spoken command the performer can use instead of touching the phone. */
enum class VoiceCommand { Start, Stop }

/** What the app should do for a [VoiceCommand] in the current [SessionState]. */
enum class VoiceAction { StartCalibration, StartTake, StopTake, Cancel, None }

/**
 * Finds a command in recognized text (partial or final): whole words "start" / "stop",
 * case-insensitive, other words ignored. If both appear, the one spoken last wins.
 */
fun matchVoiceCommand(text: String): VoiceCommand? {
    var found: VoiceCommand? = null
    val word = StringBuilder()
    fun flush() {
        when (word.toString().lowercase()) {
            "start" -> found = VoiceCommand.Start
            "stop" -> found = VoiceCommand.Stop
        }
        word.clear()
    }
    for (c in text) if (c.isLetter() || c == '\'') word.append(c) else flush()
    flush()
    return found
}

/** Maps a command to the same action the matching button performs; [VoiceAction.None] if it does not apply now. */
fun resolveVoiceCommand(state: SessionState, command: VoiceCommand): VoiceAction = when (command) {
    VoiceCommand.Start -> when {
        state.step == Step.Calibration && state.calibrationPhase == CalibrationPhase.Ready -> VoiceAction.StartCalibration
        state.step == Step.Takes && state.takePhase == TakePhase.List -> VoiceAction.StartTake
        else -> VoiceAction.None
    }
    VoiceCommand.Stop -> when {
        state.step == Step.Takes && state.takePhase == TakePhase.Recording -> VoiceAction.StopTake
        state.step == Step.Takes && state.takePhase == TakePhase.Countdown -> VoiceAction.Cancel
        // Calibration has a fixed length, so Stop aborts it like the Cancel button.
        state.step == Step.Calibration && state.calibrationPhase in
            setOf(CalibrationPhase.Countdown, CalibrationPhase.APose, CalibrationPhase.Turn) -> VoiceAction.Cancel
        else -> VoiceAction.None
    }
}

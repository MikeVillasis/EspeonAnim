package io.github.mikevillasis.espeonanim.shared.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceCommandsTest {
    @Test fun matchesWholeWordsCaseInsensitive() {
        assertEquals(VoiceCommand.Start, matchVoiceCommand("start"))
        assertEquals(VoiceCommand.Start, matchVoiceCommand("Start!"))
        assertEquals(VoiceCommand.Stop, matchVoiceCommand("STOP"))
        assertEquals(VoiceCommand.Start, matchVoiceCommand("okay please start now"))
    }

    @Test fun ignoresOtherWordsAndPartialWordMatches() {
        assertNull(matchVoiceCommand(""))
        assertNull(matchVoiceCommand("hello there"))
        assertNull(matchVoiceCommand("restart"))
        assertNull(matchVoiceCommand("starting stopped"))
        assertNull(matchVoiceCommand("stopwatch"))
    }

    @Test fun lastSpokenCommandWins() {
        assertEquals(VoiceCommand.Stop, matchVoiceCommand("start stop"))
        assertEquals(VoiceCommand.Start, matchVoiceCommand("stop no start"))
    }

    private fun cal(p: CalibrationPhase) = SessionState(step = Step.Calibration, calibrationPhase = p)
    private fun takes(p: TakePhase) = SessionState(step = Step.Takes, takePhase = p)

    @Test fun startResolves() {
        assertEquals(VoiceAction.StartCalibration, resolveVoiceCommand(cal(CalibrationPhase.Ready), VoiceCommand.Start))
        assertEquals(VoiceAction.StartTake, resolveVoiceCommand(takes(TakePhase.List), VoiceCommand.Start))
        for (p in CalibrationPhase.entries.filter { it != CalibrationPhase.Ready }) {
            assertEquals(VoiceAction.None, resolveVoiceCommand(cal(p), VoiceCommand.Start), "calibration $p")
        }
        for (p in TakePhase.entries.filter { it != TakePhase.List }) {
            assertEquals(VoiceAction.None, resolveVoiceCommand(takes(p), VoiceCommand.Start), "takes $p")
        }
        assertEquals(VoiceAction.None, resolveVoiceCommand(SessionState(step = Step.Setup), VoiceCommand.Start))
        assertEquals(VoiceAction.None, resolveVoiceCommand(SessionState(step = Step.Package), VoiceCommand.Start))
    }

    @Test fun stopResolves() {
        assertEquals(VoiceAction.StopTake, resolveVoiceCommand(takes(TakePhase.Recording), VoiceCommand.Stop))
        assertEquals(VoiceAction.Cancel, resolveVoiceCommand(takes(TakePhase.Countdown), VoiceCommand.Stop))
        for (p in listOf(CalibrationPhase.Countdown, CalibrationPhase.APose, CalibrationPhase.Turn)) {
            assertEquals(VoiceAction.Cancel, resolveVoiceCommand(cal(p), VoiceCommand.Stop), "calibration $p")
        }
        for (p in listOf(CalibrationPhase.Ready, CalibrationPhase.Finalizing, CalibrationPhase.Review)) {
            assertEquals(VoiceAction.None, resolveVoiceCommand(cal(p), VoiceCommand.Stop), "calibration $p")
        }
        for (p in listOf(TakePhase.List, TakePhase.Finalizing, TakePhase.Review)) {
            assertEquals(VoiceAction.None, resolveVoiceCommand(takes(p), VoiceCommand.Stop), "takes $p")
        }
        assertEquals(VoiceAction.None, resolveVoiceCommand(SessionState(step = Step.Setup), VoiceCommand.Stop))
    }
}

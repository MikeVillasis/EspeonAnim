package io.github.mikevillasis.espeonanim.shared

import io.github.mikevillasis.espeonanim.shared.guidance.TakeSummary
import io.github.mikevillasis.espeonanim.shared.manifest.AppInfo
import io.github.mikevillasis.espeonanim.shared.manifest.DeviceInfo
import io.github.mikevillasis.espeonanim.shared.manifest.TakeRole
import io.github.mikevillasis.espeonanim.shared.manifest.Verdict
import io.github.mikevillasis.espeonanim.shared.session.CalibrationPhase
import io.github.mikevillasis.espeonanim.shared.session.CaptureSession
import io.github.mikevillasis.espeonanim.shared.session.RecordedClip
import io.github.mikevillasis.espeonanim.shared.session.SessionEffect
import io.github.mikevillasis.espeonanim.shared.session.Step
import io.github.mikevillasis.espeonanim.shared.session.TakePhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CaptureSessionTest {
    private fun clip(name: String, verdict: Verdict? = Verdict.Ok) = RecordedClip(
        videoPath = "/tmp/$name.mp4", imuPath = "/tmp/$name.imu.csv", fps = 59.94, width = 1920, height = 1080,
        rotationDeg = 90, durationS = 6.0, focalLengthPx = 1450.0, lensFacing = "back",
        summary = verdict?.let { TakeSummary(it, 100, emptyList(), "x") },
    )

    private fun CaptureSession.tickTo(t: Long) = tick(t)

    private fun CaptureSession.doCalibration(): Long {
        continueFromSetup()
        startCalibration(0)
        assertEquals(CalibrationPhase.Countdown, state.value.calibrationPhase)
        val start = tick(3_000)
        assertTrue(start.single() is SessionEffect.StartRecording)
        assertEquals(CalibrationPhase.APose, state.value.calibrationPhase)
        tick(6_000)
        assertEquals(CalibrationPhase.Turn, state.value.calibrationPhase)
        val stop = tick(16_000)
        assertEquals(listOf(SessionEffect.StopRecording), stop)
        onRecordingFinished(clip("calibration", null))
        assertEquals(CalibrationPhase.Review, state.value.calibrationPhase)
        acceptCalibration()
        assertEquals(Step.Takes, state.value.step)
        return 16_000
    }

    private fun CaptureSession.recordTake(from: Long, verdict: Verdict, name: String): Long {
        startTake(from)
        assertEquals(TakePhase.Countdown, state.value.takePhase)
        val s = tick(from + 3_000)
        assertTrue((s.single() as SessionEffect.StartRecording).role == TakeRole.Action)
        assertEquals(listOf(SessionEffect.StopRecording), tick(from + 3_000 + 8_000)) // auto-stop at 8 s
        onRecordingFinished(clip(name, verdict))
        assertEquals(TakePhase.Review, state.value.takePhase)
        return from + 12_000
    }

    @Test
    fun fullFlowProducesPackage() {
        val s = CaptureSession()
        s.setPerformerHeight(178)
        var t = s.doCalibration()
        assertFalse(s.state.value.canExport)
        t = s.recordTake(t, Verdict.Ok, "a1")
        s.keepTake()
        assertEquals(1, s.state.value.keptCount)
        assertNotNull(s.state.value.takeAdvice, "recommends a second take")
        assertTrue(s.state.value.canExport)
        t = s.recordTake(t, Verdict.Warn, "a2")
        s.keepTake()
        assertEquals(null, s.state.value.takeAdvice)
        assertTrue(s.goToPackage())
        val plan = s.buildPackage("2026-11-09T03:12:00Z", AppInfo("EspeonAnim", "0.1.0", "android"), DeviceInfo("G", "P", "13"))
        assertEquals(listOf("action_01", "action_02", "calibration"), plan.manifest.takes.map { it.id })
        assertEquals(178, plan.manifest.performer.heightCm)
        assertEquals("takes/action_01.mp4", plan.manifest.takes[0].file)
        assertEquals("takes/calibration.imu.csv", plan.manifest.takes[2].imuFile)
        assertEquals(6, plan.files.size)
        // Round trip through JSON to be sure the plan is valid.
        io.github.mikevillasis.espeonanim.shared.manifest.Manifest.parse(plan.manifest.toJson())
    }

    @Test
    fun poorOnlyTakesCannotBeExported() {
        val s = CaptureSession()
        var t = s.doCalibration()
        t = s.recordTake(t, Verdict.Poor, "p1")
        s.keepTake()
        assertFalse(s.state.value.canExport)
        assertFalse(s.goToPackage())
        t = s.recordTake(t, Verdict.Warn, "w1")
        s.keepTake()
        assertTrue(s.goToPackage())
    }

    @Test
    fun retakeDeletesFilesAndRestartsCountdown() {
        val s = CaptureSession()
        var t = s.doCalibration()
        t = s.recordTake(t, Verdict.Ok, "a1")
        val fx = s.retakeTake(t)
        assertEquals(TakePhase.Countdown, s.state.value.takePhase)
        assertEquals(listOf("/tmp/a1.mp4", "/tmp/a1.imu.csv"), (fx.single() as SessionEffect.DeleteFiles).paths)
        assertEquals(0, s.state.value.keptCount)
    }

    @Test
    fun cancelDuringCountdownAndRecording() {
        val s = CaptureSession()
        val t = s.doCalibration()
        s.startTake(t)
        assertTrue(s.cancel().isEmpty())
        assertEquals(TakePhase.List, s.state.value.takePhase)
        s.startTake(t)
        s.tick(t + 3_000)
        assertEquals(listOf(SessionEffect.StopRecording), s.cancel())
        // The clip arriving after cancel is deleted, not reviewed.
        val fx = s.onRecordingFinished(clip("late"))
        assertTrue(fx.single() is SessionEffect.DeleteFiles)
        assertEquals(TakePhase.List, s.state.value.takePhase)
    }

    @Test
    fun countdownRemainingIsReported() {
        val s = CaptureSession()
        s.continueFromSetup()
        s.startCalibration(1_000)
        s.tick(2_000)
        assertEquals(2_000, s.state.value.remainingMs)
    }

    @Test
    fun takeIdsAreNotReused() {
        val s = CaptureSession()
        var t = s.doCalibration()
        t = s.recordTake(t, Verdict.Ok, "a1")
        s.retakeTake(t)
        s.tick(t + 3_000)
        assertEquals("action_02", s.state.value.recordingId)
    }
}

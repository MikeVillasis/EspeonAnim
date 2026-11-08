package io.github.mikevillasis.espeonanim.shared

import io.github.mikevillasis.espeonanim.shared.guidance.TakeSummary
import io.github.mikevillasis.espeonanim.shared.manifest.AppInfo
import io.github.mikevillasis.espeonanim.shared.manifest.DeviceInfo
import io.github.mikevillasis.espeonanim.shared.manifest.TakeRole
import io.github.mikevillasis.espeonanim.shared.manifest.Verdict
import io.github.mikevillasis.espeonanim.shared.session.CalibrationPhase
import io.github.mikevillasis.espeonanim.shared.session.CaptureSession
import io.github.mikevillasis.espeonanim.shared.session.RecordedClip
import io.github.mikevillasis.espeonanim.shared.session.SessionConfig
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
        val start = tick(5_000)
        assertTrue(start.single() is SessionEffect.StartRecording)
        assertEquals(CalibrationPhase.APose, state.value.calibrationPhase)
        tick(8_000)
        assertEquals(CalibrationPhase.Turn, state.value.calibrationPhase)
        val stop = tick(18_000)
        assertEquals(listOf(SessionEffect.StopRecording), stop)
        onRecordingFinished(clip("calibration", null))
        assertEquals(CalibrationPhase.Review, state.value.calibrationPhase)
        acceptCalibration()
        assertEquals(Step.Takes, state.value.step)
        return 18_000
    }

    private fun CaptureSession.recordTake(from: Long, verdict: Verdict, name: String): Long {
        startTake(from)
        assertEquals(TakePhase.Countdown, state.value.takePhase)
        val s = tick(from + 5_000)
        assertTrue((s.single() as SessionEffect.StartRecording).role == TakeRole.Action)
        assertEquals(listOf(SessionEffect.StopRecording), tick(from + 5_000 + 12_000)) // fallback stop at 12 s
        onRecordingFinished(clip(name, verdict))
        assertEquals(TakePhase.Review, state.value.takePhase)
        return from + 18_000
    }

    @Test
    fun actionDefaultsToJumpAndCanBeChangedOnSetupOnly() {
        val s = CaptureSession()
        assertEquals("jump", s.state.value.action)
        s.setAction("other")
        assertEquals("other", s.state.value.action)
        s.setAction("dance") // unknown values are ignored
        assertEquals("other", s.state.value.action)
        s.setAction("jump")
        assertEquals("jump", s.state.value.action)
        s.setAction("other")
        s.continueFromSetup()
        s.setAction("jump") // not on Setup any more
        assertEquals("other", s.state.value.action)
    }

    @Test
    fun chosenActionGoesIntoManifestAndSurvivesStartOver() {
        val s = CaptureSession()
        s.setAction("other")
        var t = s.doCalibration()
        t = s.recordTake(t, Verdict.Ok, "a1")
        s.keepTake()
        assertTrue(s.goToPackage())
        val plan = s.buildPackage("2026-11-09T03:12:00Z", AppInfo("EspeonAnim", "0.1.0", "android"), DeviceInfo("G", "P", "13"))
        assertEquals("other", plan.manifest.action)
        assertEquals("other", io.github.mikevillasis.espeonanim.shared.manifest.Manifest.parse(plan.manifest.toJson()).action)
        s.startOver()
        assertEquals("other", s.state.value.action)
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
        s.tick(t + 5_000)
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
        assertEquals(4_000, s.state.value.remainingMs)
    }

    @Test
    fun takeIdsAreNotReused() {
        val s = CaptureSession()
        var t = s.doCalibration()
        t = s.recordTake(t, Verdict.Ok, "a1")
        s.retakeTake(t)
        s.tick(t + 5_000)
        assertEquals("action_02", s.state.value.recordingId)
    }

    @Test
    fun defaultsAreFiveSecondCountdownAndTwelveSecondFallback() {
        val c = SessionConfig()
        assertEquals(5_000, c.countdownMs)
        assertEquals(12_000, c.maxTakeMs)
    }

    @Test
    fun countdownBeepsOncePerSecond() {
        val s = CaptureSession()
        s.continueFromSetup()
        s.startCalibration(0)
        val beeps = ArrayList<Int>()
        var t = 0L
        while (t < 5_000) {
            s.tick(t).filterIsInstance<SessionEffect.CountdownBeep>().forEach { beeps += it.secondsLeft }
            t += 50
        }
        assertEquals(listOf(5, 4, 3, 2, 1), beeps)
    }

    @Test
    fun autoStopOnlyWhileRecording() {
        val s = CaptureSession()
        val t = s.doCalibration()
        assertTrue(s.onAutoStop().isEmpty()) // list
        s.startTake(t)
        assertTrue(s.onAutoStop().isEmpty()) // countdown
        s.tick(t + 5_000)
        assertEquals(TakePhase.Recording, s.state.value.takePhase)
        assertEquals(listOf(SessionEffect.StopRecording), s.onAutoStop())
        assertEquals(TakePhase.Finalizing, s.state.value.takePhase)
        assertTrue(s.onAutoStop().isEmpty()) // already stopping
        assertTrue(s.tick(t + 5_000 + 12_000).isEmpty()) // fallback does not stop twice
        s.onRecordingFinished(clip("auto"))
        assertEquals(TakePhase.Review, s.state.value.takePhase)
    }

    @Test
    fun startOverResetsEverythingAndDeletesClipFiles() {
        val s = CaptureSession()
        s.setPerformerHeight(180)
        var t = s.doCalibration()
        t = s.recordTake(t, Verdict.Ok, "a1")
        s.keepTake()
        t = s.recordTake(t, Verdict.Ok, "a2") // left pending
        val effects = s.startOver()
        val expected = listOf("calibration", "a1", "a2").flatMap { listOf("/tmp/$it.mp4", "/tmp/$it.imu.csv") }
        val deleted = effects.filterIsInstance<SessionEffect.DeleteFiles>().flatMap { it.paths }
        assertEquals(expected.sorted(), deleted.sorted())
        val st = s.state.value
        assertEquals(Step.Setup, st.step)
        assertEquals(null, st.performerHeightCm)
        assertEquals(null, st.calibration)
        assertEquals(null, st.pending)
        assertTrue(st.takes.isEmpty())
        assertEquals(CalibrationPhase.Ready, st.calibrationPhase)
        assertEquals(TakePhase.List, st.takePhase)
    }

    @Test
    fun startOverRefusedWhileRecording() {
        val s = CaptureSession()
        val t = s.doCalibration()
        s.startTake(t)
        s.tick(t + 5_000)
        assertEquals(TakePhase.Recording, s.state.value.takePhase)
        assertTrue(s.startOver().isEmpty())
        assertEquals(Step.Takes, s.state.value.step)
        assertEquals(TakePhase.Recording, s.state.value.takePhase)
        // also refused during the countdown and while finalizing
        s.stopTakeEarly()
        assertEquals(TakePhase.Finalizing, s.state.value.takePhase)
        assertTrue(s.startOver().isEmpty())
        assertEquals(Step.Takes, s.state.value.step)
        val c = CaptureSession()
        c.continueFromSetup(); c.startCalibration(0)
        assertTrue(c.startOver().isEmpty())
        assertEquals(Step.Calibration, c.state.value.step)
    }

    @Test
    fun takeIdsRestartAfterStartOver() {
        val s = CaptureSession()
        var t = s.doCalibration()
        t = s.recordTake(t, Verdict.Ok, "a1")
        s.keepTake()
        s.startOver()
        t = s.doCalibration()
        s.startTake(t)
        val e = s.tick(t + 5_000)
        assertEquals("action_01", (e.single() as SessionEffect.StartRecording).takeId)
    }
}

package io.github.mikevillasis.espeonanim.shared.session

import io.github.mikevillasis.espeonanim.shared.guidance.TakeSummary
import io.github.mikevillasis.espeonanim.shared.manifest.AppInfo
import io.github.mikevillasis.espeonanim.shared.manifest.CameraInfo
import io.github.mikevillasis.espeonanim.shared.manifest.DeviceInfo
import io.github.mikevillasis.espeonanim.shared.manifest.Manifest
import io.github.mikevillasis.espeonanim.shared.manifest.Performer
import io.github.mikevillasis.espeonanim.shared.manifest.TakeEntry
import io.github.mikevillasis.espeonanim.shared.manifest.TakeGuidance
import io.github.mikevillasis.espeonanim.shared.manifest.TakeRole
import io.github.mikevillasis.espeonanim.shared.manifest.Verdict
import io.github.mikevillasis.espeonanim.shared.packaging.PackageFile
import io.github.mikevillasis.espeonanim.shared.packaging.PackagePlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Timings and limits of a capture session. */
data class SessionConfig(
    /** Countdown before calibration and action takes; long enough to get back into place. */
    val countdownMs: Long = 5_000,
    /** Calibration: hold an A-pose first ... */
    val aPoseMs: Long = 3_000,
    /** ... then turn slowly through 360 degrees in place. */
    val turnMs: Long = 10_000,
    /**
     * Fallback limit: action takes stop by themselves after this long. Normally the jump
     * detector ([JumpStopDetector]) stops the take earlier, after the landing.
     */
    val maxTakeMs: Long = 12_000,
    /** The app recommends at least this many kept takes. */
    val recommendedKeptTakes: Int = 2,
    val action: String = "jump",
)

enum class Step { Setup, Calibration, Takes, Package }
enum class CalibrationPhase { Ready, Countdown, APose, Turn, Finalizing, Review }
enum class TakePhase { List, Countdown, Recording, Finalizing, Review }

/** A finished recording, reported by the platform camera layer. */
data class RecordedClip(
    val videoPath: String,
    val imuPath: String?,
    val fps: Double,
    val width: Int,
    val height: Int,
    val rotationDeg: Int,
    val durationS: Double,
    val focalLengthPx: Double?,
    val lensFacing: String,
    /** Guidance verdict of the take; null for calibration. */
    val summary: TakeSummary? = null,
)

data class Take(
    val id: String,
    val role: TakeRole,
    val clip: RecordedClip,
    val kept: Boolean,
)

/** Things the UI/platform layer must do in response to a session change. */
sealed interface SessionEffect {
    data class StartRecording(val takeId: String, val role: TakeRole) : SessionEffect
    data object StopRecording : SessionEffect

    /** One countdown second began; [secondsLeft] counts down to 1. The platform plays a beep. */
    data class CountdownBeep(val secondsLeft: Int) : SessionEffect

    /** Recording that was cancelled or rejected; delete its files. */
    data class DeleteFiles(val paths: List<String>) : SessionEffect
}

data class SessionState(
    val step: Step = Step.Setup,
    val performerHeightCm: Int? = null,
    val action: String = "jump",
    val calibrationPhase: CalibrationPhase = CalibrationPhase.Ready,
    /** Accepted calibration clip. */
    val calibration: Take? = null,
    val takePhase: TakePhase = TakePhase.List,
    /** Kept action takes. */
    val takes: List<Take> = emptyList(),
    /** Clip waiting for keep/redo (calibration or action take). */
    val pending: Take? = null,
    /** Id of the clip currently being recorded. */
    val recordingId: String? = null,
    /** Milliseconds left in the current countdown/phase; updated by tick(). */
    val remainingMs: Long = 0,
    val error: String? = null,
    val recommendedKeptTakes: Int = 2,
) {
    val capturing: Boolean
        get() = (step == Step.Calibration && calibrationPhase in setOf(CalibrationPhase.Countdown, CalibrationPhase.APose, CalibrationPhase.Turn)) ||
            (step == Step.Takes && takePhase in setOf(TakePhase.Countdown, TakePhase.Recording))

    val keptCount: Int get() = takes.count { it.kept }
    val hasUsableTake: Boolean get() = takes.any { it.kept && it.clip.summary?.verdict != Verdict.Poor }

    /** Needed before export: a calibration clip and at least one kept, non-poor take. */
    val canExport: Boolean get() = calibration != null && hasUsableTake

    /** Plain-language status of the take requirements, or null if all satisfied. */
    val takeAdvice: String?
        get() = when {
            keptCount == 0 -> "Record at least $recommendedKeptTakes takes, then keep the best ones."
            !hasUsableTake -> "All kept takes may give bad results. Keep at least one take without a \"poor\" rating."
            keptCount < recommendedKeptTakes -> "One more take is recommended: the best one is picked automatically."
            else -> null
        }
}

/**
 * Capture flow state machine: Setup, Calibration, Takes, Package. Pure logic with an injected
 * clock: the UI calls [tick] regularly with a monotonic millisecond clock and carries out the
 * returned [SessionEffect]s.
 */
class CaptureSession(private val config: SessionConfig = SessionConfig()) {
    private val _state = MutableStateFlow(
        SessionState(action = config.action, recommendedKeptTakes = config.recommendedKeptTakes),
    )
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var phaseStartMs = 0L
    private var takeCounter = 0
    private var lastBeepSecond = -1

    private inline fun update(f: (SessionState) -> SessionState) {
        _state.value = f(_state.value)
    }

    // ---- Setup ----

    fun setPerformerHeight(cm: Int?) = update { it.copy(performerHeightCm = cm?.takeIf { h -> h in 50..250 }) }

    fun continueFromSetup() = update {
        if (it.step == Step.Setup) it.copy(step = Step.Calibration, calibrationPhase = CalibrationPhase.Ready, error = null) else it
    }

    fun backToSetup() = update { if (it.capturing) it else it.copy(step = Step.Setup) }

    // ---- Calibration ----

    fun startCalibration(nowMs: Long) {
        val s = _state.value
        if (s.step != Step.Calibration || s.calibrationPhase != CalibrationPhase.Ready) return
        phaseStartMs = nowMs
        lastBeepSecond = -1
        _state.value = s.copy(
            calibrationPhase = CalibrationPhase.Countdown, remainingMs = config.countdownMs, error = null,
        )
    }

    /** Keep the calibration clip and move on to the action takes. */
    fun acceptCalibration() {
        val s = _state.value
        val p = s.pending ?: return
        if (s.step != Step.Calibration || s.calibrationPhase != CalibrationPhase.Review) return
        _state.value = s.copy(
            step = Step.Takes, calibration = p, pending = null, calibrationPhase = CalibrationPhase.Ready,
            takePhase = TakePhase.List,
        )
    }

    fun redoCalibration(): List<SessionEffect> {
        val s = _state.value
        val p = s.pending ?: return emptyList()
        _state.value = s.copy(pending = null, calibrationPhase = CalibrationPhase.Ready)
        return listOf(SessionEffect.DeleteFiles(p.clip.filePaths()))
    }

    // ---- Takes ----

    fun startTake(nowMs: Long) {
        val s = _state.value
        if (s.step != Step.Takes || s.takePhase != TakePhase.List) return
        phaseStartMs = nowMs
        lastBeepSecond = -1
        _state.value = s.copy(takePhase = TakePhase.Countdown, remainingMs = config.countdownMs, error = null)
    }

    /** Stop a running take early (the user tapped stop). */
    fun stopTakeEarly(): List<SessionEffect> {
        val s = _state.value
        if (s.step != Step.Takes || s.takePhase != TakePhase.Recording) return emptyList()
        _state.value = s.copy(takePhase = TakePhase.Finalizing, remainingMs = 0)
        return listOf(SessionEffect.StopRecording)
    }

    /**
     * The jump detector decided the performer has landed and settled. Same as
     * [stopTakeEarly]: only acts while an action take is recording.
     */
    fun onAutoStop(): List<SessionEffect> = stopTakeEarly()

    fun keepTake() {
        val s = _state.value
        val p = s.pending ?: return
        if (s.step != Step.Takes || s.takePhase != TakePhase.Review) return
        _state.value = s.copy(takes = s.takes + p.copy(kept = true), pending = null, takePhase = TakePhase.List)
    }

    /** Discard the reviewed take and start a new countdown right away. */
    fun retakeTake(nowMs: Long): List<SessionEffect> {
        val s = _state.value
        val p = s.pending ?: return emptyList()
        if (s.step != Step.Takes || s.takePhase != TakePhase.Review) return emptyList()
        phaseStartMs = nowMs
        lastBeepSecond = -1
        _state.value = s.copy(pending = null, takePhase = TakePhase.Countdown, remainingMs = config.countdownMs)
        return listOf(SessionEffect.DeleteFiles(p.clip.filePaths()))
    }

    /** Discard the reviewed take and return to the list. */
    fun discardPending(): List<SessionEffect> {
        val s = _state.value
        val p = s.pending ?: return emptyList()
        _state.value = s.copy(pending = null, takePhase = TakePhase.List)
        return listOf(SessionEffect.DeleteFiles(p.clip.filePaths()))
    }

    /** Remove a kept take from the list. */
    fun deleteTake(id: String): List<SessionEffect> {
        val s = _state.value
        val t = s.takes.firstOrNull { it.id == id } ?: return emptyList()
        _state.value = s.copy(takes = s.takes - t)
        return listOf(SessionEffect.DeleteFiles(t.clip.filePaths()))
    }

    /** Open a kept take for review (play + summary); keep/discard act on it again. */
    fun reviewTake(id: String) {
        val s = _state.value
        val t = s.takes.firstOrNull { it.id == id } ?: return
        if (s.takePhase != TakePhase.List) return
        _state.value = s.copy(takes = s.takes - t, pending = t, takePhase = TakePhase.Review)
    }

    // ---- Cancel / recording lifecycle ----

    /** Abort a countdown or a running recording. */
    fun cancel(): List<SessionEffect> {
        val s = _state.value
        return when {
            s.step == Step.Calibration && s.calibrationPhase == CalibrationPhase.Countdown -> {
                _state.value = s.copy(calibrationPhase = CalibrationPhase.Ready); emptyList()
            }
            s.step == Step.Takes && s.takePhase == TakePhase.Countdown -> {
                _state.value = s.copy(takePhase = TakePhase.List); emptyList()
            }
            s.step == Step.Calibration && s.calibrationPhase in setOf(CalibrationPhase.APose, CalibrationPhase.Turn) -> {
                _state.value = s.copy(calibrationPhase = CalibrationPhase.Ready, recordingId = null)
                listOf(SessionEffect.StopRecording)
            }
            s.step == Step.Takes && s.takePhase == TakePhase.Recording -> {
                _state.value = s.copy(takePhase = TakePhase.List, recordingId = null)
                listOf(SessionEffect.StopRecording)
            }
            else -> emptyList()
        }
    }

    /** The platform finished writing the video. A cancelled recording's clip is discarded. */
    fun onRecordingFinished(clip: RecordedClip): List<SessionEffect> {
        val s = _state.value
        val id = s.recordingId
        val role = if (s.step == Step.Calibration) TakeRole.Calibration else TakeRole.Action
        val waiting = (s.step == Step.Calibration && s.calibrationPhase == CalibrationPhase.Finalizing) ||
            (s.step == Step.Takes && s.takePhase == TakePhase.Finalizing)
        if (!waiting || id == null) {
            return listOf(SessionEffect.DeleteFiles(clip.filePaths())) // was cancelled meanwhile
        }
        val take = Take(id, role, clip, kept = false)
        _state.value = if (role == TakeRole.Calibration) {
            s.copy(pending = take, calibrationPhase = CalibrationPhase.Review, recordingId = null, remainingMs = 0)
        } else {
            s.copy(pending = take, takePhase = TakePhase.Review, recordingId = null, remainingMs = 0)
        }
        return emptyList()
    }

    fun onRecordingFailed(message: String) {
        val s = _state.value
        _state.value = s.copy(
            calibrationPhase = if (s.step == Step.Calibration) CalibrationPhase.Ready else s.calibrationPhase,
            takePhase = if (s.step == Step.Takes) TakePhase.List else s.takePhase,
            recordingId = null, error = message,
        )
    }

    fun clearError() = update { it.copy(error = null) }

    /** Drives countdowns and automatic phase changes. Call about 10-30 times per second. */
    fun tick(nowMs: Long): List<SessionEffect> {
        val s = _state.value
        val elapsed = nowMs - phaseStartMs
        if (s.step == Step.Calibration) {
            when (s.calibrationPhase) {
                CalibrationPhase.Countdown -> {
                    if (elapsed >= config.countdownMs) {
                        val id = "calibration"
                        phaseStartMs = nowMs
                        _state.value = s.copy(
                            calibrationPhase = CalibrationPhase.APose, recordingId = id, remainingMs = config.aPoseMs,
                        )
                        return listOf(SessionEffect.StartRecording(id, TakeRole.Calibration))
                    }
                    _state.value = s.copy(remainingMs = config.countdownMs - elapsed)
                    return beepFor(config.countdownMs - elapsed)
                }
                CalibrationPhase.APose -> {
                    if (elapsed >= config.aPoseMs) {
                        phaseStartMs = nowMs
                        _state.value = s.copy(calibrationPhase = CalibrationPhase.Turn, remainingMs = config.turnMs)
                    } else {
                        _state.value = s.copy(remainingMs = config.aPoseMs - elapsed)
                    }
                }
                CalibrationPhase.Turn -> {
                    if (elapsed >= config.turnMs) {
                        _state.value = s.copy(calibrationPhase = CalibrationPhase.Finalizing, remainingMs = 0)
                        return listOf(SessionEffect.StopRecording)
                    }
                    _state.value = s.copy(remainingMs = config.turnMs - elapsed)
                }
                else -> Unit
            }
        } else if (s.step == Step.Takes) {
            when (s.takePhase) {
                TakePhase.Countdown -> {
                    if (elapsed >= config.countdownMs) {
                        takeCounter++
                        val id = "action_" + takeCounter.toString().padStart(2, '0')
                        phaseStartMs = nowMs
                        _state.value = s.copy(takePhase = TakePhase.Recording, recordingId = id, remainingMs = config.maxTakeMs)
                        return listOf(SessionEffect.StartRecording(id, TakeRole.Action))
                    }
                    _state.value = s.copy(remainingMs = config.countdownMs - elapsed)
                    return beepFor(config.countdownMs - elapsed)
                }
                TakePhase.Recording -> {
                    if (elapsed >= config.maxTakeMs) {
                        _state.value = s.copy(takePhase = TakePhase.Finalizing, remainingMs = 0)
                        return listOf(SessionEffect.StopRecording)
                    }
                    _state.value = s.copy(remainingMs = config.maxTakeMs - elapsed)
                }
                else -> Unit
            }
        }
        return emptyList()
    }

    /** A [SessionEffect.CountdownBeep] the first time each whole second is shown. */
    private fun beepFor(remainingMs: Long): List<SessionEffect> {
        val sec = ((remainingMs + 999) / 1000).toInt()
        if (sec == lastBeepSecond || sec <= 0) return emptyList()
        lastBeepSecond = sec
        return listOf(SessionEffect.CountdownBeep(sec))
    }

    // ---- Start over ----

    /**
     * Throw away the calibration, takes and height and go back to Setup for a new performer.
     * Refused (no change, no effects) while a countdown, recording or finalizing is in progress.
     */
    fun startOver(): List<SessionEffect> {
        val s = _state.value
        val busy = s.capturing ||
            (s.step == Step.Calibration && s.calibrationPhase == CalibrationPhase.Finalizing) ||
            (s.step == Step.Takes && s.takePhase == TakePhase.Finalizing)
        if (busy) return emptyList()
        val paths = (listOfNotNull(s.calibration, s.pending) + s.takes).flatMap { it.clip.filePaths() }.distinct()
        _state.value = SessionState(action = config.action, recommendedKeptTakes = config.recommendedKeptTakes)
        phaseStartMs = 0L
        takeCounter = 0
        lastBeepSecond = -1
        return if (paths.isEmpty()) emptyList() else listOf(SessionEffect.DeleteFiles(paths))
    }

    // ---- Package ----

    fun goToPackage(): Boolean {
        val s = _state.value
        if (s.step != Step.Takes || s.takePhase != TakePhase.List || !s.canExport) return false
        _state.value = s.copy(step = Step.Package)
        return true
    }

    fun backToTakes() = update { if (it.step == Step.Package) it.copy(step = Step.Takes, takePhase = TakePhase.List) else it }

    /** Manifest and the files to put in the `.espeon` zip (kept takes plus calibration). */
    fun buildPackage(createdUtc: String, app: AppInfo, device: DeviceInfo): PackagePlan {
        val s = _state.value
        val cal = requireNotNull(s.calibration) { "Calibration clip is missing." }
        require(s.canExport) { "Need at least one kept take that is not rated poor." }
        val files = ArrayList<PackageFile>()
        val entries = ArrayList<TakeEntry>()
        for (t in s.takes.filter { it.kept }.sortedBy { it.id } + cal) {
            val base = "takes/${t.id}"
            files += PackageFile("$base.mp4", t.clip.videoPath)
            t.clip.imuPath?.let { files += PackageFile("$base.imu.csv", it) }
            entries += TakeEntry(
                id = t.id,
                role = t.role,
                file = "$base.mp4",
                imuFile = t.clip.imuPath?.let { "$base.imu.csv" },
                fps = t.clip.fps,
                width = t.clip.width,
                height = t.clip.height,
                rotationDeg = t.clip.rotationDeg,
                durationS = t.clip.durationS,
                camera = CameraInfo(t.clip.focalLengthPx, t.clip.lensFacing),
                guidance = t.clip.summary?.let { TakeGuidance(it.verdict, it.warningCodes) },
            )
        }
        val manifest = Manifest(
            createdUtc = createdUtc,
            app = app,
            device = device,
            performer = Performer(s.performerHeightCm),
            action = s.action,
            takes = entries,
        )
        return PackagePlan(manifest, files)
    }

    private fun RecordedClip.filePaths(): List<String> = listOfNotNull(videoPath, imuPath)
}

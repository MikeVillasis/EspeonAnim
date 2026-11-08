package io.github.mikevillasis.espeonanim

import android.app.Application
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import androidx.camera.view.PreviewView
import androidx.camera.video.VideoRecordEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import io.github.mikevillasis.espeonanim.camera.CameraController
import io.github.mikevillasis.espeonanim.camera.ImuRecorder
import io.github.mikevillasis.espeonanim.camera.PoseAnalyzer
import io.github.mikevillasis.espeonanim.camera.PoseFrame
import io.github.mikevillasis.espeonanim.export.Exporter
import io.github.mikevillasis.espeonanim.shared.guidance.FrameInput
import io.github.mikevillasis.espeonanim.shared.guidance.GuidanceConfig
import io.github.mikevillasis.espeonanim.shared.guidance.GuidanceEngine
import io.github.mikevillasis.espeonanim.shared.guidance.Landmark
import io.github.mikevillasis.espeonanim.shared.guidance.LiveStatus
import io.github.mikevillasis.espeonanim.shared.manifest.AppInfo
import io.github.mikevillasis.espeonanim.shared.manifest.DeviceInfo
import io.github.mikevillasis.espeonanim.shared.manifest.TakeRole
import io.github.mikevillasis.espeonanim.shared.packaging.AndroidPackageWriter
import io.github.mikevillasis.espeonanim.shared.session.CaptureSession
import io.github.mikevillasis.espeonanim.shared.session.RecordedClip
import io.github.mikevillasis.espeonanim.shared.session.SessionEffect
import io.github.mikevillasis.espeonanim.shared.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Landmarks to draw, in upright frame coordinates. */
data class OverlayState(val landmarks: List<Landmark>, val frameAspect: Float, val mirrored: Boolean)

sealed interface ExportState {
    data object Idle : ExportState
    data object Building : ExportState
    data class Ready(val file: File, val sizeBytes: Long, val savedTo: String? = null, val note: String? = null) : ExportState
    data class Failed(val message: String) : ExportState
}

class CaptureViewModel(app: Application) : AndroidViewModel(app) {
    private val appContext = app
    private val session = CaptureSession()
    val state: StateFlow<SessionState> = session.state

    private val engine = GuidanceEngine(GuidanceConfig())
    private val engineLock = Any()
    private val imu = ImuRecorder(app)
    private val controller = CameraController(app)
    private var analyzer: PoseAnalyzer? = null

    private val _live = MutableStateFlow(LiveStatus())
    val live: StateFlow<LiveStatus> = _live.asStateFlow()
    private val _overlay = MutableStateFlow<OverlayState?>(null)
    val overlay: StateFlow<OverlayState?> = _overlay.asStateFlow()
    private val _front = MutableStateFlow(false)
    val front: StateFlow<Boolean> = _front.asStateFlow()
    private val _cameraError = MutableStateFlow<String?>(null)
    val cameraError: StateFlow<String?> = _cameraError.asStateFlow()
    private val _export = MutableStateFlow<ExportState>(ExportState.Idle)
    val export: StateFlow<ExportState> = _export.asStateFlow()
    /** True when the camera runs at 60 fps; false means 30 fps. */
    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps.asStateFlow()

    private val captureDir = File(app.filesDir, "captures")

    init {
        captureDir.deleteRecursively() // leftovers from a previous process; session state is in memory only
        captureDir.mkdirs()
        viewModelScope.launch {
            while (true) {
                delay(50)
                val effects = session.tick(SystemClock.elapsedRealtime())
                if (effects.isNotEmpty()) apply(effects)
            }
        }
    }

    // ---- Setup / flow ----
    fun setHeight(text: String) = session.setPerformerHeight(text.toIntOrNull())
    fun continueFromSetup() = session.continueFromSetup()
    fun backToSetup() = session.backToSetup()
    fun startCalibration() = session.startCalibration(SystemClock.elapsedRealtime())
    fun acceptCalibration() = session.acceptCalibration()
    fun redoCalibration() = apply(session.redoCalibration())
    fun startTake() = session.startTake(SystemClock.elapsedRealtime())
    fun stopTakeEarly() = apply(session.stopTakeEarly())
    fun cancel() = apply(session.cancel())
    fun keepTake() = session.keepTake()
    fun retakeTake() = apply(session.retakeTake(SystemClock.elapsedRealtime()))
    fun discardPending() = apply(session.discardPending())
    fun deleteTake(id: String) = apply(session.deleteTake(id))
    fun reviewTake(id: String) = session.reviewTake(id)
    fun clearError() = session.clearError()
    fun goToPackage() { if (session.goToPackage()) { _export.value = ExportState.Idle; buildPackage() } }
    fun backToTakes() = session.backToTakes()

    fun toggleFacing() {
        if (!state.value.capturing) _front.value = !_front.value
    }

    // ---- Camera ----
    fun bindCamera(owner: LifecycleOwner, previewView: PreviewView, rotation: Int) {
        viewModelScope.launch {
            try {
                _cameraError.value = null
                imu.start()
                val a = analyzer ?: PoseAnalyzer(
                    appContext,
                    onFrame = ::onPose,
                    onError = { t -> _cameraError.value = "Pose analysis problem: ${t.message}" },
                ).also { analyzer = it }
                controller.bind(owner, previewView, _front.value, a, rotation)
                _fps.value = controller.targetFps
            } catch (t: Throwable) {
                _cameraError.value = t.message ?: "The camera could not be opened."
            }
        }
    }

    fun setRotation(rotation: Int) = controller.setRotation(rotation)

    fun releaseCamera() {
        controller.release()
        imu.stop()
        _overlay.value = null
        _live.value = LiveStatus()
        synchronized(engineLock) { engine.reset() }
    }

    private fun onPose(f: PoseFrame) {
        val lens = controller.lens
        val portrait = f.uprightHeight >= f.uprightWidth
        val input = FrameInput(
            timestampMs = f.timestampMs,
            peopleCount = f.peopleCount,
            landmarks = f.landmarks,
            meanLuma = f.luma.mean(),
            lumaContrast = if (f.landmarks.isEmpty()) null else f.luma.bodyContrast(f.landmarks),
            gravity = imu.gravity,
            gyroMagnitude = imu.gyroMagnitude,
            fps = controller.targetFps.toFloat(),
            verticalFovDeg = lens?.verticalFovDeg(portrait) ?: GuidanceConfig.DEFAULT_VERTICAL_FOV_DEG,
        )
        val status = synchronized(engineLock) { engine.update(input) }
        _live.value = status
        _overlay.value = OverlayState(f.landmarks, f.uprightWidth.toFloat() / f.uprightHeight, _front.value)
    }

    // ---- Recording ----
    private fun apply(effects: List<SessionEffect>) {
        for (e in effects) when (e) {
            is SessionEffect.StartRecording -> startRecording(e.takeId, e.role)
            SessionEffect.StopRecording -> controller.stopRecording()
            is SessionEffect.DeleteFiles -> viewModelScope.launch(Dispatchers.IO) {
                e.paths.forEach { runCatching { File(it).delete() } }
            }
        }
    }

    private fun startRecording(takeId: String, role: TakeRole) {
        val video = File(captureDir, "$takeId.mp4")
        val imuFile = File(captureDir, "$takeId.imu.csv")
        var startNs = 0L
        imu.beginBuffering()
        if (role == TakeRole.Action) synchronized(engineLock) { engine.startTake() }
        try {
            controller.startRecording(video) { ev ->
                when (ev) {
                    is VideoRecordEvent.Start -> startNs = SystemClock.elapsedRealtimeNanos()
                    is VideoRecordEvent.Finalize -> onFinalized(ev, role, video, imuFile, startNs)
                    else -> Unit
                }
            }
        } catch (t: Throwable) {
            imu.discardBuffer()
            session.onRecordingFailed(t.message ?: "Recording could not start.")
        }
    }

    private fun onFinalized(ev: VideoRecordEvent.Finalize, role: TakeRole, video: File, imuFile: File, startNs: Long) {
        val endNs = SystemClock.elapsedRealtimeNanos()
        val summary = if (role == TakeRole.Action) synchronized(engineLock) { engine.finishTake() } else null
        if (ev.hasError() && (!video.exists() || video.length() == 0L)) {
            imu.discardBuffer()
            session.onRecordingFailed("Recording failed (code ${ev.error}). Please try again.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val meta = readVideoMeta(video, controller.targetFps.toDouble())
                val wrote = startNs > 0 && imu.writeCsv(imuFile, startNs, endNs)
                if (!wrote) imu.discardBuffer()
                val lens = controller.lens
                val clip = RecordedClip(
                    videoPath = video.absolutePath,
                    imuPath = if (wrote) imuFile.absolutePath else null,
                    fps = meta.fps, width = meta.width, height = meta.height, rotationDeg = meta.rotation,
                    durationS = meta.durationS,
                    focalLengthPx = lens?.focalLengthPx(maxOf(meta.width, meta.height)),
                    lensFacing = lens?.facing ?: if (_front.value) "front" else "back",
                    summary = summary,
                )
                withContext(Dispatchers.Main) { apply(session.onRecordingFinished(clip)) }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { session.onRecordingFailed("Could not read the recording: ${t.message}") }
            }
        }
    }

    private class VideoMeta(val width: Int, val height: Int, val rotation: Int, val durationS: Double, val fps: Double)

    private fun readVideoMeta(file: File, fallbackFps: Double): VideoMeta {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            fun int(key: Int) = r.extractMetadata(key)?.toIntOrNull()
            val w = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: 1920
            val h = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: 1080
            val rot = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val frames = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
            val fps = when {
                frames != null && frames > 1 && durMs > 0 -> frames * 1000.0 / durMs
                else -> r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
                    ?: fallbackFps
            }
            return VideoMeta(w, h, rot, durMs / 1000.0, Math.round(fps * 100) / 100.0)
        } finally {
            r.release()
        }
    }

    // ---- Export ----
    private fun buildPackage() {
        _export.value = ExportState.Building
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
                val plan = session.buildPackage(
                    createdUtc = now.toString(),
                    app = AppInfo("EspeonAnim", BuildConfig.VERSION_NAME, "android"),
                    device = DeviceInfo(
                        manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
                        model = Build.MODEL,
                        osVersion = Build.VERSION.RELEASE,
                    ),
                )
                val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC).format(now)
                val dir = Exporter.exportDir(appContext)
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, "EspeonAnim_$stamp.espeon")
                AndroidPackageWriter().write(plan, out.absolutePath)
                _export.value = ExportState.Ready(out, out.length())
            } catch (t: Throwable) {
                _export.value = ExportState.Failed(t.message ?: "Could not build the package.")
            }
        }
    }

    fun saveToDownloads() {
        val s = _export.value as? ExportState.Ready ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val where = Exporter.saveToDownloads(appContext, s.file)
                _export.value = s.copy(savedTo = where)
            } catch (t: Throwable) {
                _export.value = s.copy(note = "Saving failed: ${t.message}")
            }
        }
    }

    fun shareIntent() = (_export.value as? ExportState.Ready)?.let { Exporter.shareIntent(appContext, it.file) }

    fun retryExport() = buildPackage()

    override fun onCleared() {
        controller.shutdown()
        imu.stop()
        analyzer?.close()
    }
}

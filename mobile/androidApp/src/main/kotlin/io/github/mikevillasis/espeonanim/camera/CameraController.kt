package io.github.mikevillasis.espeonanim.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Owns the CameraX use cases: Preview + VideoCapture (1080p, 60 fps when supported)
 * + low-res ImageAnalysis feeding the pose landmarker.
 */
@androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
class CameraController(private val context: Context) {

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var analysis: ImageAnalysis? = null
    private var recording: Recording? = null

    /** Lens details of the bound camera (focal length, fps ranges). */
    var lens: LensInfo? = null
        private set

    /** Frame rate requested from the camera (60 or 30). */
    var targetFps: Int = 30
        private set

    /**
     * Binds the camera to [owner]'s lifecycle. [analyzer] receives low-res frames.
     * Throws if the camera cannot be opened; callers should surface the message.
     */
    suspend fun bind(
        owner: LifecycleOwner,
        previewView: PreviewView,
        front: Boolean,
        analyzer: ImageAnalysis.Analyzer,
        rotation: Int,
    ) {
        val p = ProcessCameraProvider.awaitInstance(context)
        provider = p
        p.unbindAll()
        recording = null

        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val info = selector.filter(p.availableCameraInfos).firstOrNull()
            ?: throw IllegalStateException(if (front) "No front camera found." else "No back camera found.")
        val camId = Camera2CameraInfo.from(info).cameraId
        val lensInfo = LensInfo.load(context, camId)
        lens = lensInfo
        val range = lensInfo.pickFpsRange(60)
        targetFps = range.upper

        val ratio16x9 = AspectRatioStrategy(AspectRatio.RATIO_16_9, AspectRatioStrategy.FALLBACK_RULE_AUTO)

        val previewBuilder = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio16x9).build())
            .setTargetRotation(rotation)
        Camera2Interop.Extender(previewBuilder)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
        val pv = previewBuilder.build().also { it.surfaceProvider = previewView.surfaceProvider }

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)))
            .setExecutor(ContextCompat.getMainExecutor(context))
            .build()
        val vcBuilder = VideoCapture.Builder(recorder)
            .setTargetFrameRate(range)
            .setTargetRotation(rotation)
        Camera2Interop.Extender(vcBuilder)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
        val vc = vcBuilder.build()

        val ia = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(ratio16x9)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(640, 360), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setTargetRotation(rotation)
            .build()
        ia.setAnalyzer(analysisExecutor, analyzer)

        p.bindToLifecycle(owner, selector, pv, vc, ia)
        preview = pv
        videoCapture = vc
        analysis = ia
    }

    fun setRotation(rotation: Int) {
        preview?.targetRotation = rotation
        analysis?.targetRotation = rotation
        // A running recording keeps the rotation it started with.
        if (recording == null) videoCapture?.targetRotation = rotation
    }

    @SuppressLint("MissingPermission") // audio is never enabled
    fun startRecording(file: File, onEvent: (VideoRecordEvent) -> Unit) {
        val vc = videoCapture ?: throw IllegalStateException("Camera is not ready.")
        file.parentFile?.mkdirs()
        val options = FileOutputOptions.Builder(file).build()
        recording = vc.output
            .prepareRecording(context, options) // no withAudioEnabled(): clips are silent
            .start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) recording = null
                onEvent(event)
            }
    }

    fun stopRecording() {
        recording?.stop()
    }

    val isRecording: Boolean get() = recording != null

    fun release() {
        recording?.stop()
        recording = null
        analysis?.clearAnalyzer()
        provider?.unbindAll()
        preview = null; videoCapture = null; analysis = null
    }

    fun shutdown() {
        release()
        analysisExecutor.shutdown()
    }
}

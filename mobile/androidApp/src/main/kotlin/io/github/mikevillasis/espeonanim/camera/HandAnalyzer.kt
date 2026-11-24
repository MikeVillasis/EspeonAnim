package io.github.mikevillasis.espeonanim.camera

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import io.github.mikevillasis.espeonanim.shared.guidance.Landmark
import io.github.mikevillasis.espeonanim.shared.guidance.LumaGrid
import java.util.concurrent.ConcurrentHashMap

/** One analyzed POV frame: the hands found plus the luma grid of the same image. */
class HandFrame(
    val timestampMs: Long,
    /** Up to two hands, each 21 landmarks in upright image coordinates (0-1). */
    val hands: List<List<Landmark>>,
    val luma: LumaGrid,
    val uprightWidth: Int,
    val uprightHeight: Int,
)

/**
 * CameraX analyzer feeding MediaPipe Hand Landmarker (up to 2 hands) in LIVE_STREAM mode, used instead
 * of [PoseAnalyzer] in POV mode. Same threading as [PoseAnalyzer]: [analyze] runs on the camera's
 * analysis executor and only throttles, converts the frame to an upright bitmap and queues it;
 * MediaPipe detects on its own thread and calls back [onFrame] there. Landmarks come back in the
 * upright image's normalized coordinates because the bitmap is rotated by the frame's
 * `rotationDegrees` before detection (the same trick as the pose analyzer, so rotation changes are
 * handled by CameraX's target rotation alone). POV always uses the back camera, so nothing is mirrored.
 */
class HandAnalyzer(
    context: Context,
    private val targetFps: Int = 15,
    private val onFrame: (HandFrame) -> Unit,
    private val onError: (Throwable) -> Unit,
) : ImageAnalysis.Analyzer {

    private class Pending(val luma: LumaGrid, val w: Int, val h: Int)

    private val pending = ConcurrentHashMap<Long, Pending>()
    private val minIntervalMs = 1000L / targetFps
    private var lastAcceptedMs = 0L
    private var lastTimestamp = 0L

    private val landmarker: HandLandmarker? = try {
        val base = BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> handleResult(result) }
            .setErrorListener { e -> onError(e) }
            .build()
        HandLandmarker.createFromOptions(context.applicationContext, options)
    } catch (t: Throwable) {
        onError(t)
        null
    }

    override fun analyze(image: ImageProxy) {
        try {
            val lm = landmarker ?: return
            val now = SystemClock.elapsedRealtime()
            if (now - lastAcceptedMs < minIntervalMs) return
            lastAcceptedMs = now

            val rotation = image.imageInfo.rotationDegrees
            val grid = image.uprightLumaGrid(rotation)
            val upright = image.toUprightBitmap(rotation)
            // MediaPipe needs strictly increasing timestamps.
            var ts = now
            if (ts <= lastTimestamp) ts = lastTimestamp + 1
            lastTimestamp = ts
            pending[ts] = Pending(grid, upright.width, upright.height)
            // Drop entries that never produced a result.
            if (pending.size > 8) pending.keys.filter { it < ts - 2000 }.forEach { pending.remove(it) }
            lm.detectAsync(BitmapImageBuilder(upright).build(), ts)
        } catch (t: Throwable) {
            onError(t)
        } finally {
            image.close()
        }
    }

    private fun handleResult(result: HandLandmarkerResult) {
        val ts = result.timestampMs()
        val p = pending.remove(ts) ?: return
        val hands = result.landmarks().map { hand -> hand.map { Landmark(it.x(), it.y()) } }
        onFrame(HandFrame(ts, hands, p.luma, p.w, p.h))
    }

    fun close() {
        try { landmarker?.close() } catch (_: Throwable) { }
    }

    companion object {
        const val MODEL_ASSET = "hand_landmarker.task"
    }
}

package io.github.mikevillasis.espeonanim.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import io.github.mikevillasis.espeonanim.shared.guidance.Landmark
import io.github.mikevillasis.espeonanim.shared.guidance.LumaGrid
import java.util.concurrent.ConcurrentHashMap

/** One analyzed frame: pose result plus the luma grid of the same image. */
class PoseFrame(
    val timestampMs: Long,
    val peopleCount: Int,
    /** 33 landmarks of the primary person (upright, un-mirrored image coordinates) or empty. */
    val landmarks: List<Landmark>,
    val luma: LumaGrid,
    val uprightWidth: Int,
    val uprightHeight: Int,
)

/**
 * CameraX analyzer feeding MediaPipe Pose Landmarker (lite) in LIVE_STREAM mode.
 * Throttled to about [targetFps]; the heavy work happens in MediaPipe's own thread.
 */
class PoseAnalyzer(
    context: Context,
    private val targetFps: Int = 15,
    private val onFrame: (PoseFrame) -> Unit,
    private val onError: (Throwable) -> Unit,
) : ImageAnalysis.Analyzer {

    private class Pending(val luma: LumaGrid, val w: Int, val h: Int)

    private val pending = ConcurrentHashMap<Long, Pending>()
    private val minIntervalMs = 1000L / targetFps
    private var lastAcceptedMs = 0L
    private var lastTimestamp = 0L

    private val landmarker: PoseLandmarker? = try {
        val base = BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(2) // 2 so a second person can be reported (multiple_people)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> handleResult(result) }
            .setErrorListener { e -> onError(e) }
            .build()
        PoseLandmarker.createFromOptions(context.applicationContext, options)
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
            val grid = buildLumaGrid(image, rotation)
            val bitmap = image.toBitmap()
            val upright = if (rotation == 0) bitmap else {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            }
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

    private fun handleResult(result: PoseLandmarkerResult) {
        val ts = result.timestampMs()
        val p = pending.remove(ts) ?: return
        val people = result.landmarks()
        val primary = people.firstOrNull()?.map {
            Landmark(it.x(), it.y(), it.visibility().orElse(1f))
        }.orEmpty()
        onFrame(PoseFrame(ts, people.size, primary, p.luma, p.w, p.h))
    }

    fun close() {
        try { landmarker?.close() } catch (_: Throwable) { }
    }

    /** Builds an upright, down-sampled luma image from the Y plane. */
    private fun buildLumaGrid(image: ImageProxy, rotation: Int): LumaGrid {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        val w = image.width
        val h = image.height
        val uw = if (rotation == 90 || rotation == 270) h else w
        val uh = if (rotation == 90 || rotation == 270) w else h
        val gw = GRID_BASE
        val gh = (GRID_BASE.toFloat() * uh / uw).toInt().coerceAtLeast(8)
        val out = ByteArray(gw * gh)
        for (gy in 0 until gh) {
            val v = ((gy + 0.5f) / gh * uh).toInt().coerceIn(0, uh - 1)
            for (gx in 0 until gw) {
                val u = ((gx + 0.5f) / gw * uw).toInt().coerceIn(0, uw - 1)
                val sx: Int
                val sy: Int
                when (rotation) {
                    90 -> { sx = v; sy = h - 1 - u }
                    180 -> { sx = w - 1 - u; sy = h - 1 - v }
                    270 -> { sx = w - 1 - v; sy = u }
                    else -> { sx = u; sy = v }
                }
                out[gy * gw + gx] = buf.get(sy * rowStride + sx * pixStride)
            }
        }
        return LumaGrid(gw, gh, out)
    }

    companion object {
        const val MODEL_ASSET = "pose_landmarker_lite.task"
        private const val GRID_BASE = 48
    }
}

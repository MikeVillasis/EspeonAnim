package io.github.mikevillasis.espeonanim.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Range
import kotlin.math.atan

/**
 * Camera2 characteristics needed for the manifest (focal length in pixels), live guidance
 * (field of view) and frame-rate selection.
 */
class LensInfo(
    val cameraId: String,
    val facing: String,
    /** Real focal length of the main lens in mm (LENS_INFO_AVAILABLE_FOCAL_LENGTHS). */
    private val focalMm: Float?,
    /** Physical sensor width (the long edge) in mm. */
    private val sensorWidthMm: Float?,
    /** LENS_INTRINSIC_CALIBRATION fx and the pixel-array width it refers to, when the device reports it. */
    private val calibFx: Float?,
    private val calibArrayWidth: Int?,
    val fpsRanges: List<Range<Int>>,
) {
    /**
     * Focal length in pixels at a stored video width ([storedWidth], the long edge, e.g. 1920).
     * Assumes the 16:9 video keeps the full sensor width (true for 4:3 and 16:9 sensors) and no digital zoom.
     * Prefers the factory calibration (LENS_INTRINSIC_CALIBRATION) over mm/sensor-size.
     */
    fun focalLengthPx(storedWidth: Int): Double? {
        if (calibFx != null && calibArrayWidth != null && calibArrayWidth > 0) {
            return calibFx.toDouble() * storedWidth / calibArrayWidth
        }
        if (focalMm != null && sensorWidthMm != null && sensorWidthMm > 0f) {
            return focalMm.toDouble() * storedWidth / sensorWidthMm
        }
        return null
    }

    /** Vertical field of view in degrees of the upright frame; null if unknown. */
    fun verticalFovDeg(portrait: Boolean, frameLongSide: Int = 1920, frameShortSide: Int = 1080): Float? {
        val f = focalLengthPx(frameLongSide) ?: return null
        val side = if (portrait) frameLongSide else frameShortSide
        return Math.toDegrees(2.0 * atan(side / (2.0 * f))).toFloat()
    }

    /**
     * 60 fps if the device offers a fixed 60 fps AE range, else a fixed 30 fps range,
     * else the supported range closest to 30.
     */
    fun pickFpsRange(wanted: Int = 60): Range<Int> {
        fpsRanges.firstOrNull { it.lower == wanted && it.upper == wanted }?.let { return it }
        fpsRanges.firstOrNull { it.lower == 30 && it.upper == 30 }?.let { return it }
        return fpsRanges.minByOrNull { kotlin.math.abs(it.upper - 30) * 100 + (it.upper - it.lower) }
            ?: Range(30, 30)
    }

    val supports60: Boolean get() = fpsRanges.any { it.lower == 60 && it.upper == 60 }

    companion object {
        fun load(context: Context, cameraId: String): LensInfo {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val c = cm.getCameraCharacteristics(cameraId)
            val facing = if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) "front" else "back"
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val calib = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            val calibArray = c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
                ?: c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
            return LensInfo(
                cameraId = cameraId,
                facing = facing,
                focalMm = focal,
                sensorWidthMm = phys?.let { maxOf(it.width, it.height) },
                calibFx = calib?.takeIf { it.size >= 1 && it[0] > 0f }?.get(0),
                calibArrayWidth = calibArray?.let { maxOf(it.width(), it.height()) },
                fpsRanges = ranges,
            )
        }
    }
}

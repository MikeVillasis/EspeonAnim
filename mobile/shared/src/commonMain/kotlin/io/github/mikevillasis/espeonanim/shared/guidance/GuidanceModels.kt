package io.github.mikevillasis.espeonanim.shared.guidance

import io.github.mikevillasis.espeonanim.shared.manifest.Verdict

/** One normalized pose landmark: x,y in [0,1] of the upright frame (may fall outside when off-screen). */
data class Landmark(val x: Float, val y: Float, val visibility: Float = 1f)

/** Indices of the 33 MediaPipe pose landmarks that the guidance rules use. */
object Lm {
    const val COUNT = 33
    const val NOSE = 0
    const val LEFT_EYE = 2
    const val RIGHT_EYE = 5
    const val LEFT_EAR = 7
    const val RIGHT_EAR = 8
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LEFT_KNEE = 25
    const val RIGHT_KNEE = 26
    const val LEFT_ANKLE = 27
    const val RIGHT_ANKLE = 28
    const val LEFT_HEEL = 29
    const val RIGHT_HEEL = 30
    const val LEFT_FOOT_INDEX = 31
    const val RIGHT_FOOT_INDEX = 32

    val HEAD = intArrayOf(NOSE, LEFT_EYE, RIGHT_EYE, LEFT_EAR, RIGHT_EAR)
    val FEET = intArrayOf(LEFT_ANKLE, RIGHT_ANKLE, LEFT_HEEL, RIGHT_HEEL, LEFT_FOOT_INDEX, RIGHT_FOOT_INDEX)
    val HIPS = intArrayOf(LEFT_HIP, RIGHT_HIP)
    val SHOULDERS = intArrayOf(LEFT_SHOULDER, RIGHT_SHOULDER)
    val LIMB_TIPS = intArrayOf(LEFT_WRIST, RIGHT_WRIST, LEFT_ANKLE, RIGHT_ANKLE)

    /** Skeleton bones for drawing overlays. */
    val BONES: List<Pair<Int, Int>> = listOf(
        11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16,
        11 to 23, 12 to 24, 23 to 24,
        23 to 25, 25 to 27, 24 to 26, 26 to 28,
        27 to 29, 29 to 31, 28 to 30, 30 to 32,
        0 to 11, 0 to 12,
    )
}

/** Device gravity as reported by Android TYPE_GRAVITY (device frame, m/s^2; points toward the sky). */
data class Vec3(val x: Float, val y: Float, val z: Float)

/** Everything the engine needs about one analyzed frame (about 15 per second). */
data class FrameInput(
    val timestampMs: Long,
    /** Number of people the pose detector found. */
    val peopleCount: Int,
    /** 33 landmarks of the primary person, or empty when nobody was found. */
    val landmarks: List<Landmark>,
    /** Mean luma of the whole frame, 0-255. */
    val meanLuma: Float,
    /** |mean luma of the body - mean luma of the surroundings|, 0-255; null if unknown. */
    val lumaContrast: Float? = null,
    val gravity: Vec3? = null,
    /** Gyroscope magnitude in rad/s; null if no gyroscope. */
    val gyroMagnitude: Float? = null,
    /** Recording frame rate in fps; 0 or less means unknown. */
    val fps: Float = 0f,
    /** Vertical field of view of the upright frame, degrees (used for camera height). */
    val verticalFovDeg: Float = GuidanceConfig.DEFAULT_VERTICAL_FOV_DEG,
    /** True for the selfie camera: it looks along +z of the device, the back camera along -z. */
    val frontCamera: Boolean = false,
)

data class GuidanceMessage(
    val code: WarningCode,
    val severity: Severity,
    val message: String,
    val suggestion: String,
) {
    companion object {
        fun of(code: WarningCode) = GuidanceMessage(code, code.severity, code.message, code.suggestion)
    }
}

/** What is currently wrong, most important first (blocks before warnings). */
data class LiveStatus(
    val active: List<GuidanceMessage> = emptyList(),
) {
    val top: GuidanceMessage? get() = active.firstOrNull()
    val hasBlock: Boolean get() = active.any { it.severity == Severity.Block }

    /** True when nothing blocks recording. */
    val ready: Boolean get() = !hasBlock
    fun has(code: WarningCode) = active.any { it.code == code }
}

data class TakeWarning(
    val code: WarningCode,
    /** Fraction of analyzed frames (0-1) in which the condition held. */
    val fraction: Float,
    val message: String,
    val suggestion: String,
)

data class TakeSummary(
    val verdict: Verdict,
    val analyzedFrames: Int,
    val warnings: List<TakeWarning>,
    /** One plain sentence for the user. */
    val headline: String,
    /** Plain-language reasons footage may give bad results; empty unless [verdict] is Poor. */
    val poorReasons: List<String> = emptyList(),
) {
    val warningCodes: List<String> get() = warnings.map { it.code.code }
    val mayGiveBadResults: Boolean get() = verdict == Verdict.Poor
}

/**
 * Region of the (upright) frame where the performer's whole body should fit when standing:
 * about 40-65% of the height, centered, leaving headroom for a jump.
 */
object FrameGuide {
    const val LEFT = 0.30f
    const val RIGHT = 0.70f
    const val TOP = 0.20f
    const val BOTTOM = 0.80f
}

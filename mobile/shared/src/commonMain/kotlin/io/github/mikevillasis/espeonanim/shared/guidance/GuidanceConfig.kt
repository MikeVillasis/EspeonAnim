package io.github.mikevillasis.espeonanim.shared.guidance

/**
 * All guidance thresholds in one place. Defaults were chosen without device testing
 * and are expected to be tuned. "enter" = condition starts; "hold" = condition persists
 * (hysteresis, so values near a threshold do not flicker). Debounce times are in ms.
 */
data class GuidanceConfig(
    // ---- Light (mean luma 0-255 of the whole frame, smoothed ~1 s). Phone auto-exposure
    // brightens dim rooms, so "dark" starts fairly high.
    val lowLightEnter: Float = 60f,
    val lowLightHold: Float = 70f,

    // ---- Contrast: |body luma - surrounding luma| (0-255), smoothed ~1 s.
    val lowContrastEnter: Float = 15f,
    val lowContrastHold: Float = 20f,

    // ---- Framing. Height fraction = (feet bottom - estimated head top) / frame height.
    /** Landmarks closer than this to the frame edge count as cropped. */
    val cropMargin: Float = 0.02f,
    /** Head top is estimated this fraction of body height above the highest head landmark. */
    val headTopExtra: Float = 0.05f,
    /** Feet landmarks with mean visibility below this (with hips visible) are treated as out of frame. */
    val feetVisibilityMin: Float = 0.35f,
    val hipsVisibleMin: Float = 0.5f,
    val tooFarEnter: Float = 0.50f,
    val tooFarHold: Float = 0.55f,
    val tooCloseEnter: Float = 0.90f,
    val tooCloseHold: Float = 0.87f,

    // ---- Camera height. Offset between hips and camera axis, in body heights, estimated from
    // hip position, pitch and field of view. Waist ~ hip level, so ~0.2 body heights (~35 cm) is clearly off.
    val cameraHeightEnter: Float = 0.22f,
    val cameraHeightHold: Float = 0.17f,

    // ---- Tilt (degrees). Roll is measured from the nearest 90 degrees so landscape is fine.
    val pitchEnterDeg: Float = 12f,
    val pitchHoldDeg: Float = 9f,
    val rollEnterDeg: Float = 8f,
    val rollHoldDeg: Float = 6f,

    // ---- Camera movement: smoothed (~0.3 s) gyroscope magnitude in rad/s. Propped phones read
    // < 0.03; hand-held phones usually 0.1-0.5.
    val gyroMovingEnter: Float = 0.12f,
    val gyroMovingHold: Float = 0.08f,

    // ---- Motion blur proxy: fast wrists/ankles (body heights per second) in dim-ish light
    // (auto-exposure lengthens shutter when luma is low).
    val blurLimbSpeedEnter: Float = 2.5f,
    val blurLimbSpeedHold: Float = 1.8f,
    val blurLumaMax: Float = 100f,

    /** Recording fps below this triggers low_fps ("under 30 fps"; 29.97 does not count). */
    val lowFpsBelow: Float = 29f,

    // ---- Time windows: condition must hold continuously for onMs to show, and be clear for offMs to hide.
    val onMs: Map<WarningCode, Long> = mapOf(
        WarningCode.NoPerson to 800, WarningCode.MultiplePeople to 600, WarningCode.BodyCropped to 400,
        WarningCode.CameraMoving to 600, WarningCode.LowLight to 2000, WarningCode.LowContrast to 2000,
        WarningCode.CameraLow to 2000, WarningCode.CameraHigh to 2000, WarningCode.CameraTilted to 1000,
        WarningCode.TooFar to 1500, WarningCode.TooClose to 1500, WarningCode.MotionBlur to 500,
        WarningCode.LowFps to 0,
    ),
    val offMs: Map<WarningCode, Long> = mapOf(
        WarningCode.NoPerson to 400, WarningCode.MultiplePeople to 800, WarningCode.BodyCropped to 600,
        WarningCode.CameraMoving to 1000, WarningCode.LowLight to 2000, WarningCode.LowContrast to 2000,
        WarningCode.CameraLow to 1500, WarningCode.CameraHigh to 1500, WarningCode.CameraTilted to 800,
        WarningCode.TooFar to 1000, WarningCode.TooClose to 1000, WarningCode.MotionBlur to 800,
        WarningCode.LowFps to 0,
    ),

    // ---- Take summary.
    /** A block code is reported on a take once it affects at least this fraction of frames. */
    val reportBlockFraction: Float = 0.02f,
    /** A warn code is reported once it affects at least this fraction of frames. */
    val reportWarnFraction: Float = 0.20f,
    /** Fewer analyzed frames than this and the take cannot be judged (rated poor). */
    val minAnalyzedFrames: Int = 8,
    /** poor: head/feet cropped in more than this fraction of frames (spec: 5%). */
    val poorCroppedFraction: Float = 0.05f,
    val poorNoPersonFraction: Float = 0.25f,
    val poorMultiplePeopleFraction: Float = 0.10f,
    val poorCameraMovingFraction: Float = 0.20f,
    /** poor: low_light AND low_contrast each persistent for this fraction of frames. */
    val poorLightAndContrastFraction: Float = 0.50f,
    /** poor: dark and blurry together. */
    val poorBlurFraction: Float = 0.30f,
    val poorLowLightWithBlurFraction: Float = 0.50f,
    /** poor: person tiny or filling the frame for most of the take. */
    val poorFramingFraction: Float = 0.70f,
) {
    companion object {
        const val DEFAULT_VERTICAL_FOV_DEG = 50f
    }
}

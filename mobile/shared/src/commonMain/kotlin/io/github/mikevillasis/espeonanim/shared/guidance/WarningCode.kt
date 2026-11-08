package io.github.mikevillasis.espeonanim.shared.guidance

enum class Severity { Block, Warn }

/**
 * Warning and block codes, docs/formats.md section 4. [code] strings are contract
 * (written into the manifest and read by the processor); [suggestion] texts follow the table.
 */
enum class WarningCode(
    val code: String,
    val severity: Severity,
    /** Short, friendly, readable from 3 m away. */
    val message: String,
    val suggestion: String,
) {
    NoPerson("no_person", Severity.Block, "I can't see you", "Step into frame"),
    MultiplePeople("multiple_people", Severity.Block, "More than one person", "Only the performer should be visible"),
    BodyCropped(
        "body_cropped", Severity.Block, "Your head or feet are cut off",
        "Step back until there's space above your head and below your feet",
    ),
    CameraMoving("camera_moving", Severity.Block, "The camera is moving", "Prop the phone up; don't hold it"),
    LowLight("low_light", Severity.Warn, "It's too dark", "Turn on more lights or face a window"),
    LowContrast(
        "low_contrast", Severity.Warn, "Clothes blend into the background",
        "Wear clothes that contrast with the wall behind you",
    ),
    CameraLow("camera_low", Severity.Warn, "Phone is too low", "Place the phone at about waist height"),
    CameraHigh("camera_high", Severity.Warn, "Phone is too high", "Place the phone at about waist height"),
    CameraTilted("camera_tilted", Severity.Warn, "Phone is tilted", "Keep the phone upright and level"),
    TooFar("too_far", Severity.Warn, "You're too far away", "Move to fill about 60-80% of the frame height"),
    TooClose("too_close", Severity.Warn, "You're too close", "Move to fill about 60-80% of the frame height"),
    MotionBlur(
        "motion_blur", Severity.Warn, "Fast motion looks blurry",
        "More light lets the camera use a faster shutter",
    ),
    LowFps("low_fps", Severity.Warn, "Frame rate is under 30 fps", "Use 60 fps if your phone supports it");

    companion object {
        fun fromCode(code: String): WarningCode? = entries.firstOrNull { it.code == code }
    }
}

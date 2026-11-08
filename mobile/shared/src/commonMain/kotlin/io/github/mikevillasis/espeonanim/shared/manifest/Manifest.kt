package io.github.mikevillasis.espeonanim.shared.manifest

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `.espeon` capture package manifest, docs/formats.md section 1.
 * Field names are the on-disk JSON names and must not change without bumping [ManifestVersion].
 */
object ManifestVersion {
    const val FORMAT = "espeon-package"
    const val CURRENT = "1.0"
    const val CURRENT_MAJOR = 1
}

@Serializable
enum class TakeRole {
    @SerialName("action") Action,
    @SerialName("calibration") Calibration,
}

@Serializable
enum class Verdict {
    @SerialName("ok") Ok,
    @SerialName("warn") Warn,
    @SerialName("poor") Poor,
}

@Serializable
data class AppInfo(
    val name: String = "EspeonAnim",
    val version: String,
    val platform: String,
)

@Serializable
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    @SerialName("os_version") val osVersion: String,
)

@Serializable
data class Performer(
    @SerialName("height_cm") val heightCm: Int? = null,
)

@Serializable
data class CameraInfo(
    @SerialName("focal_length_px") val focalLengthPx: Double? = null,
    @SerialName("lens_facing") val lensFacing: String,
    /** True when the stored video is horizontally mirrored (selfie-style). Absent in old packages = false. */
    val mirrored: Boolean = false,
)

@Serializable
data class TakeGuidance(
    val verdict: Verdict,
    /** Codes from docs/formats.md section 4 (see WarningCode.code). */
    val warnings: List<String> = emptyList(),
)

@Serializable
data class TakeEntry(
    val id: String,
    val role: TakeRole,
    val file: String,
    @SerialName("imu_file") val imuFile: String? = null,
    val fps: Double,
    val width: Int,
    val height: Int,
    @SerialName("rotation_deg") val rotationDeg: Int,
    @SerialName("duration_s") val durationS: Double,
    val camera: CameraInfo? = null,
    val guidance: TakeGuidance? = null,
)

@Serializable
data class Manifest(
    val format: String = ManifestVersion.FORMAT,
    val version: String = ManifestVersion.CURRENT,
    @SerialName("created_utc") val createdUtc: String,
    val app: AppInfo,
    val device: DeviceInfo,
    val performer: Performer = Performer(),
    val action: String = "jump",
    val takes: List<TakeEntry>,
) {
    fun toJson(): String = ManifestJson.encodeToString(serializer(), this)

    companion object {
        /**
         * Parses a manifest and rejects other formats or a newer major version
         * (formats.md: "reject, with a clear message, a newer major version").
         */
        fun parse(text: String): Manifest {
            val m = ManifestJson.decodeFromString(serializer(), text)
            require(m.format == ManifestVersion.FORMAT) {
                "Not an EspeonAnim package (format='${m.format}')."
            }
            val major = m.version.substringBefore('.').toIntOrNull()
                ?: throw IllegalArgumentException("Unreadable package version '${m.version}'.")
            require(major <= ManifestVersion.CURRENT_MAJOR) {
                "Package version ${m.version} is newer than this app understands " +
                    "(supports up to ${ManifestVersion.CURRENT_MAJOR}.x). Please update EspeonAnim."
            }
            return m
        }
    }
}

/** Shared JSON settings: nulls are omitted, unknown keys ignored for forward-compatible minors. */
val ManifestJson: Json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

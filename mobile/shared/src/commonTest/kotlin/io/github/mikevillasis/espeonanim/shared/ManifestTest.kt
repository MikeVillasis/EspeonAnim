package io.github.mikevillasis.espeonanim.shared

import io.github.mikevillasis.espeonanim.shared.manifest.AppInfo
import io.github.mikevillasis.espeonanim.shared.manifest.CameraInfo
import io.github.mikevillasis.espeonanim.shared.manifest.DeviceInfo
import io.github.mikevillasis.espeonanim.shared.manifest.Manifest
import io.github.mikevillasis.espeonanim.shared.manifest.Performer
import io.github.mikevillasis.espeonanim.shared.manifest.TakeEntry
import io.github.mikevillasis.espeonanim.shared.manifest.TakeGuidance
import io.github.mikevillasis.espeonanim.shared.manifest.TakeRole
import io.github.mikevillasis.espeonanim.shared.manifest.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManifestTest {
    private fun sample() = Manifest(
        createdUtc = "2026-11-09T03:12:00Z",
        app = AppInfo("EspeonAnim", "0.1.0", "android"),
        device = DeviceInfo("Google", "Pixel 7", "13"),
        performer = Performer(175),
        takes = listOf(
            TakeEntry(
                id = "action_01", role = TakeRole.Action, file = "takes/action_01.mp4",
                imuFile = "takes/action_01.imu.csv", fps = 59.94, width = 1920, height = 1080,
                rotationDeg = 90, durationS = 6.9, camera = CameraInfo(1450.0, "back"),
                guidance = TakeGuidance(Verdict.Ok, listOf("low_light")),
            ),
            TakeEntry(
                id = "calibration", role = TakeRole.Calibration, file = "takes/calibration.mp4",
                fps = 59.94, width = 1920, height = 1080, rotationDeg = 90, durationS = 16.0,
            ),
        ),
    )

    @Test
    fun roundTrip() {
        val m = sample()
        assertEquals(m, Manifest.parse(m.toJson()))
    }

    @Test
    fun jsonUsesContractFieldNames() {
        val json = sample().toJson()
        for (key in listOf(
            "\"format\": \"espeon-package\"", "\"version\": \"1.0\"", "\"created_utc\"", "\"os_version\"",
            "\"height_cm\": 175", "\"action\": \"jump\"", "\"imu_file\"", "\"rotation_deg\": 90", "\"duration_s\"",
            "\"focal_length_px\": 1450.0", "\"lens_facing\": \"back\"", "\"mirrored\": false", "\"verdict\": \"ok\"", "\"role\": \"action\"",
            "\"role\": \"calibration\"",
        )) assertTrue(json.contains(key), "missing $key in:\n$json")
        // Optional fields are omitted, not null.
        assertFalse(json.contains("null"))
    }

    @Test
    fun parsesSpecExample() {
        val spec = """
        {
          "format": "espeon-package", "version": "1.0", "created_utc": "2026-11-09T03:12:00Z",
          "app": { "name": "EspeonAnim", "version": "0.1.0", "platform": "android" },
          "device": { "manufacturer": "Google", "model": "Pixel 7", "os_version": "13" },
          "performer": { "height_cm": 175 }, "action": "jump",
          "takes": [
            { "id": "action_01", "role": "action", "file": "takes/action_01.mp4", "imu_file": "takes/action_01.imu.csv",
              "fps": 59.94, "width": 1920, "height": 1080, "rotation_deg": 90, "duration_s": 6.9,
              "camera": { "focal_length_px": 1450.0, "lens_facing": "back" },
              "guidance": { "verdict": "ok", "warnings": ["low_light"] } },
            { "id": "calibration", "role": "calibration", "file": "takes/calibration.mp4", "fps": 59.94,
              "width": 1920, "height": 1080, "rotation_deg": 90, "duration_s": 16.0 }
          ]
        }
        """.trimIndent()
        val m = Manifest.parse(spec)
        assertEquals(2, m.takes.size)
        assertEquals(175, m.performer.heightCm)
        assertEquals(TakeRole.Calibration, m.takes[1].role)
        assertNull(m.takes[1].guidance)
        // Old packages have no camera.mirrored: treated as not mirrored (CameraX VideoCapture default).
        assertFalse(m.takes[0].camera!!.mirrored)
        val flagged = Manifest.parse(spec.replace("\"lens_facing\": \"back\"", "\"lens_facing\": \"front\", \"mirrored\": true"))
        assertTrue(flagged.takes[0].camera!!.mirrored)
    }

    @Test
    fun heightIsOptional() {
        val m = sample().copy(performer = Performer())
        val json = m.toJson()
        assertFalse(json.contains("height_cm"))
        assertNull(Manifest.parse(json).performer.heightCm)
    }

    @Test
    fun rejectsNewerMajorVersion() {
        val json = sample().toJson().replace("\"version\": \"1.0\"", "\"version\": \"2.0\"")
        val e = assertFailsWith<IllegalArgumentException> { Manifest.parse(json) }
        assertTrue(e.message!!.contains("newer"))
    }

    @Test
    fun acceptsNewerMinorAndUnknownFields() {
        val json = sample().toJson()
            .replace("\"version\": \"1.0\"", "\"version\": \"1.4\", \"future_field\": 1")
        assertEquals("1.4", Manifest.parse(json).version)
    }

    @Test
    fun rejectsOtherFormats() {
        val json = sample().toJson().replace("espeon-package", "something-else")
        assertFailsWith<IllegalArgumentException> { Manifest.parse(json) }
    }
}

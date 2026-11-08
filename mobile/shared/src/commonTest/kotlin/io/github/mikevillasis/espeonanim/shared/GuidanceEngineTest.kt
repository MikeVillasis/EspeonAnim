package io.github.mikevillasis.espeonanim.shared

import io.github.mikevillasis.espeonanim.shared.guidance.FrameInput
import io.github.mikevillasis.espeonanim.shared.guidance.GuidanceEngine
import io.github.mikevillasis.espeonanim.shared.guidance.Landmark
import io.github.mikevillasis.espeonanim.shared.guidance.Lm
import io.github.mikevillasis.espeonanim.shared.guidance.Severity
import io.github.mikevillasis.espeonanim.shared.guidance.Vec3
import io.github.mikevillasis.espeonanim.shared.guidance.WarningCode
import io.github.mikevillasis.espeonanim.shared.manifest.Verdict
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GuidanceEngineTest {

    /** Standing person: head at [headY], feet at [feetY], hips halfway unless [hipY] is given. */
    private fun person(headY: Float = 0.15f, feetY: Float = 0.85f, hipY: Float? = null, vis: Float = 1f): List<Landmark> {
        val h = feetY - headY
        val l = MutableList(Lm.COUNT) { Landmark(0.5f, 0.5f, vis) }
        fun set(ids: IntArray, y: Float, x: Float = 0.5f) = ids.forEach { l[it] = Landmark(x, y, vis) }
        set(Lm.HEAD, headY)
        set(Lm.SHOULDERS, headY + 0.18f * h)
        set(intArrayOf(Lm.LEFT_ELBOW, Lm.RIGHT_ELBOW), headY + 0.35f * h)
        set(intArrayOf(Lm.LEFT_WRIST, Lm.RIGHT_WRIST), headY + 0.5f * h)
        set(Lm.HIPS, hipY ?: (headY + 0.5f * h))
        set(intArrayOf(Lm.LEFT_KNEE, Lm.RIGHT_KNEE), headY + 0.75f * h)
        set(Lm.FEET, feetY)
        return l
    }

    private val level = Vec3(0f, 9.81f, 0f)

    private fun frame(
        t: Long,
        landmarks: List<Landmark> = person(),
        people: Int = 1,
        luma: Float = 120f,
        contrast: Float? = 60f,
        gravity: Vec3? = level,
        gyro: Float? = 0.01f,
        fps: Float = 60f,
    ) = FrameInput(t, if (landmarks.isEmpty()) 0 else people, landmarks, luma, contrast, gravity, gyro, fps)

    /** Feeds [seconds] of frames at 15 per second starting at [from]; returns the end time. */
    private fun GuidanceEngine.feed(from: Long, seconds: Float, make: (Long) -> FrameInput): Long {
        var t = from
        val end = from + (seconds * 1000).toLong()
        while (t < end) {
            update(make(t)); t += 66
        }
        return t
    }

    private fun GuidanceEngine.has(c: WarningCode) = status.has(c)

    @Test
    fun goodFootageShowsNoMessages() {
        val e = GuidanceEngine()
        e.feed(0, 5f) { frame(it) }
        assertTrue(e.status.active.isEmpty(), "unexpected: ${e.status.active.map { it.code }}")
        assertTrue(e.status.ready)
    }

    @Test
    fun noPersonAppearsOnlyAfterDebounce() {
        val e = GuidanceEngine()
        var t = e.feed(0, 1f) { frame(it) }
        t = e.feed(t, 0.4f) { frame(it, landmarks = emptyList()) }
        assertFalse(e.has(WarningCode.NoPerson), "should not flicker on short dropouts")
        t = e.feed(t, 1f) { frame(it, landmarks = emptyList()) }
        assertTrue(e.has(WarningCode.NoPerson))
        assertEquals(Severity.Block, e.status.top?.severity)
        assertEquals("Step into frame", e.status.top?.suggestion)
        assertFalse(e.status.ready)
        // Back in frame: clears after the off window.
        e.feed(t, 1.5f) { frame(it) }
        assertFalse(e.has(WarningCode.NoPerson))
    }

    @Test
    fun alternatingDetectionsDoNotFlicker() {
        val e = GuidanceEngine()
        var t = 0L
        repeat(60) { i ->
            e.update(frame(t, landmarks = if (i % 2 == 0) person() else emptyList())); t += 66
        }
        assertFalse(e.has(WarningCode.NoPerson))
    }

    @Test
    fun multiplePeopleBlocks() {
        val e = GuidanceEngine()
        e.feed(0, 2f) { frame(it, people = 2) }
        assertTrue(e.has(WarningCode.MultiplePeople))
    }

    @Test
    fun headCroppedBlocksAndBlocksSortFirst() {
        val e = GuidanceEngine()
        e.feed(0, 3f) { frame(it, landmarks = person(headY = 0.0f), luma = 40f) }
        assertTrue(e.has(WarningCode.BodyCropped))
        assertEquals(WarningCode.BodyCropped, e.status.top?.code, "blocks must come before warnings")
        assertTrue(e.has(WarningCode.LowLight))
    }

    @Test
    fun feetOutOfFrameBlocks() {
        val e = GuidanceEngine()
        e.feed(0, 2f) { frame(it, landmarks = person(feetY = 1.0f)) }
        assertTrue(e.has(WarningCode.BodyCropped))
        val e2 = GuidanceEngine()
        // Low-visibility feet with visible hips also count as cropped.
        val lm = person().toMutableList()
        Lm.FEET.forEach { lm[it] = Landmark(0.5f, 0.99f, 0.05f) }
        e2.feed(0, 2f) { frame(it, landmarks = lm) }
        assertTrue(e2.has(WarningCode.BodyCropped))
    }

    @Test
    fun lowLightUsesHysteresis() {
        val e = GuidanceEngine()
        var t = e.feed(0, 4f) { frame(it, luma = 40f) }
        assertTrue(e.has(WarningCode.LowLight))
        // 65 is above "enter" (60) but below "hold" (70): stays on, no flicker.
        t = e.feed(t, 4f) { frame(it, luma = 65f) }
        assertTrue(e.has(WarningCode.LowLight))
        e.feed(t, 5f) { frame(it, luma = 110f) }
        assertFalse(e.has(WarningCode.LowLight))
    }

    @Test
    fun lowLightNotShownImmediately() {
        val e = GuidanceEngine()
        e.feed(0, 0.5f) { frame(it, luma = 20f) }
        assertFalse(e.has(WarningCode.LowLight))
    }

    @Test
    fun lowContrastWarns() {
        val e = GuidanceEngine()
        e.feed(0, 4f) { frame(it, contrast = 6f) }
        assertTrue(e.has(WarningCode.LowContrast))
        assertEquals(Severity.Warn, e.status.top?.severity)
        assertTrue(e.status.ready, "warnings do not block")
    }

    @Test
    fun cameraMovingFromGyro() {
        val e = GuidanceEngine()
        var t = e.feed(0, 2f) { frame(it, gyro = 0.4f) }
        assertTrue(e.has(WarningCode.CameraMoving))
        assertEquals("Prop the phone up; don't hold it", e.status.top?.suggestion)
        e.feed(t, 4f) { frame(it, gyro = 0.005f) }
        assertFalse(e.has(WarningCode.CameraMoving))
    }

    private fun gravityPitched(deg: Double): Vec3 {
        val r = deg * PI / 180
        return Vec3(0f, (9.81 * cos(r)).toFloat(), (-9.81 * sin(r)).toFloat())
    }

    @Test
    fun tiltedPhoneWarns() {
        val e = GuidanceEngine()
        e.feed(0, 3f) { frame(it, gravity = gravityPitched(25.0)) }
        assertTrue(e.has(WarningCode.CameraTilted))
        val e2 = GuidanceEngine()
        e2.feed(0, 3f) { frame(it, gravity = Vec3(2.5f, 9.4f, 0f)) } // ~15 degrees of roll
        assertTrue(e2.has(WarningCode.CameraTilted))
    }

    @Test
    fun landscapeIsNotTilted() {
        val e = GuidanceEngine()
        e.feed(0, 3f) { frame(it, gravity = Vec3(9.81f, 0.0f, 0f)) }
        assertFalse(e.has(WarningCode.CameraTilted))
    }

    @Test
    fun cameraLowAndHighFromHipPosition() {
        // Hips well above the image centre: the camera is below hip height.
        val low = GuidanceEngine()
        low.feed(0, 4f) { frame(it, landmarks = person(headY = 0.12f, feetY = 0.80f, hipY = 0.30f)) }
        assertTrue(low.has(WarningCode.CameraLow), "got ${low.status.active.map { it.code }}")
        val high = GuidanceEngine()
        high.feed(0, 4f) { frame(it, landmarks = person(headY = 0.25f, feetY = 0.95f, hipY = 0.75f)) }
        assertTrue(high.has(WarningCode.CameraHigh), "got ${high.status.active.map { it.code }}")
        val ok = GuidanceEngine()
        ok.feed(0, 4f) { frame(it) }
        assertFalse(ok.has(WarningCode.CameraLow) || ok.has(WarningCode.CameraHigh))
    }

    @Test
    fun pitchCompensatesCameraHeight() {
        // Hips appear high in frame, but the phone is tilted down by 10 degrees:
        // that explains the offset, so the camera is not reported as low.
        val e = GuidanceEngine()
        e.feed(0, 4f) {
            frame(it, landmarks = person(headY = 0.1f, feetY = 0.8f, hipY = 0.4f), gravity = gravityPitched(-10.0))
        }
        assertFalse(e.has(WarningCode.CameraLow), "got ${e.status.active.map { it.code }}")
    }

    @Test
    fun tooFarAndTooClose() {
        val far = GuidanceEngine()
        far.feed(0, 3f) { frame(it, landmarks = person(headY = 0.35f, feetY = 0.65f)) }
        assertTrue(far.has(WarningCode.TooFar))
        val close = GuidanceEngine()
        close.feed(0, 3f) { frame(it, landmarks = person(headY = 0.07f, feetY = 0.95f)) }
        assertTrue(close.has(WarningCode.TooClose))
        assertFalse(close.has(WarningCode.BodyCropped))
    }

    @Test
    fun lowFpsWarnsImmediately() {
        val e = GuidanceEngine()
        e.update(frame(0, fps = 24f))
        assertTrue(e.has(WarningCode.LowFps))
        val e2 = GuidanceEngine()
        e2.update(frame(0, fps = 29.97f))
        assertFalse(e2.has(WarningCode.LowFps))
    }

    // ---- Take summary ----

    @Test
    fun cleanTakeIsOk() {
        val e = GuidanceEngine()
        e.feed(0, 2f) { frame(it) }
        e.startTake()
        e.feed(2000, 6f) { frame(it) }
        val s = e.finishTake()
        assertEquals(Verdict.Ok, s.verdict)
        assertTrue(s.warnings.isEmpty())
        assertFalse(s.mayGiveBadResults)
    }

    @Test
    fun framesBeforeStartTakeDoNotCount() {
        val e = GuidanceEngine()
        e.feed(0, 3f) { frame(it, luma = 10f) }
        e.startTake()
        e.feed(3000, 5f) { frame(it) }
        // Smoothing needs a moment to recover, so a few early frames may still be dark.
        assertTrue(e.finishTake().warnings.none { it.code == WarningCode.LowLight && it.fraction > 0.3f })
    }

    @Test
    fun persistentLowLightAndLowContrastIsPoorAndSaysSo() {
        val e = GuidanceEngine()
        e.feed(0, 1f) { frame(it, luma = 30f, contrast = 3f) }
        e.startTake()
        e.feed(1000, 6f) { frame(it, luma = 30f, contrast = 3f) }
        val s = e.finishTake()
        assertEquals(Verdict.Poor, s.verdict)
        assertTrue(s.mayGiveBadResults)
        assertTrue(s.headline.contains("may give bad results"))
        assertTrue(s.warningCodes.containsAll(listOf("low_light", "low_contrast")))
        assertTrue(s.poorReasons.isNotEmpty())
    }

    @Test
    fun lowLightAloneIsOnlyAWarning() {
        val e = GuidanceEngine()
        e.feed(0, 1f) { frame(it, luma = 30f) }
        e.startTake()
        e.feed(1000, 6f) { frame(it, luma = 30f) }
        val s = e.finishTake()
        assertEquals(Verdict.Warn, s.verdict)
        assertEquals(listOf("low_light"), s.warningCodes)
    }

    @Test
    fun bodyCroppedInMoreThanFivePercentIsPoor() {
        val e = GuidanceEngine()
        e.feed(0, 1f) { frame(it) }
        e.startTake()
        var t = 1000L
        // 100 frames, 8 of them with the head out of the frame: 8% > 5%.
        repeat(100) { i ->
            e.update(frame(t, landmarks = if (i in 40..47) person(headY = -0.05f) else person())); t += 66
        }
        val s = e.finishTake()
        assertEquals(Verdict.Poor, s.verdict, s.headline)
        assertTrue(s.warningCodes.contains("body_cropped"))
    }

    @Test
    fun briefCroppingUnderFivePercentIsAWarning() {
        val e = GuidanceEngine()
        e.feed(0, 1f) { frame(it) }
        e.startTake()
        var t = 1000L
        repeat(100) { i ->
            e.update(frame(t, landmarks = if (i in 40..42) person(headY = -0.05f) else person())); t += 66
        }
        val s = e.finishTake()
        assertEquals(Verdict.Warn, s.verdict, s.headline)
    }

    @Test
    fun movingCameraThroughoutIsPoor() {
        val e = GuidanceEngine()
        e.startTake()
        e.feed(0, 6f) { frame(it, gyro = 0.5f) }
        val s = e.finishTake()
        assertEquals(Verdict.Poor, s.verdict)
        assertTrue(s.poorReasons.any { it.contains("moving") })
    }

    @Test
    fun tooFewFramesCannotBeJudged() {
        val e = GuidanceEngine()
        e.startTake()
        repeat(3) { e.update(frame(it * 66L)) }
        assertEquals(Verdict.Poor, e.finishTake().verdict)
    }

    @Test
    fun lowFpsTakeIsWarnWithFpsCode() {
        val e = GuidanceEngine()
        e.startTake()
        e.feed(0, 6f) { frame(it, fps = 24f) }
        val s = e.finishTake()
        assertEquals(Verdict.Warn, s.verdict)
        assertEquals(listOf("low_fps"), s.warningCodes)
        assertNotNull(s.warnings.first().suggestion)
    }

    @Test
    fun messagesMatchContractTable() {
        assertEquals("Place the phone at about waist height", WarningCode.CameraLow.suggestion)
        assertEquals(WarningCode.CameraLow.suggestion, WarningCode.CameraHigh.suggestion)
        assertEquals(13, WarningCode.entries.size)
        assertEquals(
            setOf(
                "no_person", "multiple_people", "body_cropped", "camera_moving", "low_light", "low_contrast",
                "camera_low", "camera_high", "camera_tilted", "too_far", "too_close", "motion_blur", "low_fps",
            ),
            WarningCode.entries.map { it.code }.toSet(),
        )
    }
}

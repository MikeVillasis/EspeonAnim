package io.github.mikevillasis.espeonanim.shared.guidance

import io.github.mikevillasis.espeonanim.shared.manifest.Verdict
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.asin
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Pure guidance logic: turns per-frame measurements into a debounced live status
 * ([update]) and an end-of-take verdict ([finishTake]). No platform APIs.
 *
 * Call [update] for every analyzed frame. Call [startTake] when recording begins and
 * [finishTake] when it ends; only frames between those two calls count for the summary.
 */
class GuidanceEngine(private val config: GuidanceConfig = GuidanceConfig()) {

    private class Tracker(val onMs: Long, val offMs: Long) {
        var active = false
        private var risingSince = -1L
        private var lastRaw = -1L

        fun clear() {
            active = false; risingSince = -1; lastRaw = -1
        }

        fun update(t: Long, enter: Boolean, hold: Boolean): Boolean {
            val raw = enter || (active && hold)
            if (raw) {
                lastRaw = t
                if (risingSince < 0) risingSince = t
                if (!active && t - risingSince >= onMs) active = true
            } else {
                risingSince = -1
                if (active && t - lastRaw >= offMs) active = false
            }
            return active
        }
    }

    private val trackers = WarningCode.entries.associateWith {
        Tracker(config.onMs[it] ?: 0L, config.offMs[it] ?: 0L)
    }

    // Smoothed signals.
    private var lumaEma: Float? = null
    private var contrastEma: Float? = null
    private var gyroEma: Float? = null
    private var speedEma = 0f
    private var lastT = -1L
    private var prevLandmarks: List<Landmark>? = null

    // Take accumulation.
    private var collecting = false
    private var frames = 0
    private val counts = IntArray(WarningCode.entries.size)

    private var lastStatus = LiveStatus()
    val status: LiveStatus get() = lastStatus

    /** Clears all state, including debounce windows. */
    fun reset() {
        trackers.values.forEach { it.clear() }
        lumaEma = null; contrastEma = null; gyroEma = null; speedEma = 0f
        lastT = -1; prevLandmarks = null
        collecting = false; frames = 0; counts.fill(0)
        lastStatus = LiveStatus()
    }

    fun startTake() {
        collecting = true
        frames = 0
        counts.fill(0)
    }

    fun update(input: FrameInput): LiveStatus {
        val t = input.timestampMs
        val dt = if (lastT < 0) 0L else (t - lastT).coerceAtLeast(0L)
        lastT = t

        lumaEma = smooth(lumaEma, input.meanLuma, dt, 1000f)
        contrastEma = input.lumaContrast?.let { smooth(contrastEma, it, dt, 1000f) } ?: contrastEma
        gyroEma = input.gyroMagnitude?.let { smooth(gyroEma, it, dt, 300f) } ?: gyroEma

        val raw = evaluate(input, dt)

        val active = ArrayList<GuidanceMessage>()
        for (code in WarningCode.entries) {
            val (enter, hold) = raw[code] ?: (false to false)
            val isActive = trackers.getValue(code).update(t, enter, hold)
            if (isActive) active.add(GuidanceMessage.of(code))
            if (collecting && enter) counts[code.ordinal]++
        }
        if (collecting) frames++
        // Blocks first, then warnings; enum order inside each group.
        active.sortBy { if (it.severity == Severity.Block) 0 else 1 }
        lastStatus = LiveStatus(active)
        return lastStatus
    }

    /** Raw (undebounced) per-code conditions: code -> (enter, hold). */
    private fun evaluate(input: FrameInput, dt: Long): Map<WarningCode, Pair<Boolean, Boolean>> {
        val out = HashMap<WarningCode, Pair<Boolean, Boolean>>()
        fun put(code: WarningCode, enter: Boolean, hold: Boolean = enter) {
            out[code] = enter to (hold || enter)
        }

        val lm = input.landmarks
        val hasPerson = input.peopleCount > 0 && lm.size >= Lm.COUNT

        put(WarningCode.NoPerson, !hasPerson)
        put(WarningCode.MultiplePeople, input.peopleCount >= 2)

        // Light.
        lumaEma?.let { put(WarningCode.LowLight, it < config.lowLightEnter, it < config.lowLightHold) }

        // Frame rate.
        if (input.fps > 0f) put(WarningCode.LowFps, input.fps < config.lowFpsBelow)

        // Camera movement.
        gyroEma?.let { put(WarningCode.CameraMoving, it > config.gyroMovingEnter, it > config.gyroMovingHold) }

        // Tilt / pitch.
        var pitchDeg = 0f
        input.gravity?.let { g ->
            val norm = sqrt(g.x * g.x + g.y * g.y + g.z * g.z)
            if (norm > 1f) {
                // Camera looks along -z; sin(elevation) = -gz/|g| (gravity reading points toward the sky).
                pitchDeg = toDeg(asin((-g.z / norm).coerceIn(-1f, 1f)))
                val roll = toDeg(atan2(g.x, g.y))
                // Distance from the nearest multiple of 90 degrees (portrait or landscape are both fine).
                val m = ((roll % 90f) + 90f) % 90f
                val rollDev = minOf(m, 90f - m)
                put(
                    WarningCode.CameraTilted,
                    abs(pitchDeg) > config.pitchEnterDeg || rollDev > config.rollEnterDeg,
                    abs(pitchDeg) > config.pitchHoldDeg || rollDev > config.rollHoldDeg,
                )
            }
        }

        if (!hasPerson) {
            prevLandmarks = null
            return out
        }

        // Contrast (needs a person to be meaningful).
        contrastEma?.let { put(WarningCode.LowContrast, it < config.lowContrastEnter, it < config.lowContrastHold) }

        // ---- Framing.
        val headY = lm.minOfIndexed(Lm.HEAD) { it.y }
        val feetY = lm.maxOfIndexed(Lm.FEET) { it.y }
        val bodyH0 = (feetY - headY).coerceAtLeast(0.05f)
        val headTop = headY - config.headTopExtra * bodyH0
        val feetVis = lm.meanOfIndexed(Lm.FEET) { it.visibility }
        val hipsVis = lm.meanOfIndexed(Lm.HIPS) { it.visibility }
        val feetHidden = feetVis < config.feetVisibilityMin && hipsVis > config.hipsVisibleMin
        val cropped = headTop < config.cropMargin || feetY > 1f - config.cropMargin || feetHidden
        put(WarningCode.BodyCropped, cropped)

        val heightFrac = (feetY - headTop).coerceAtLeast(0.01f)
        put(WarningCode.TooFar, !cropped && heightFrac < config.tooFarEnter, !cropped && heightFrac < config.tooFarHold)
        put(WarningCode.TooClose, !cropped && heightFrac > config.tooCloseEnter, !cropped && heightFrac > config.tooCloseHold)

        // ---- Camera height: hip ray elevation relative to horizontal, converted to body heights.
        val hipY = lm.meanOfIndexed(Lm.HIPS) { it.y }
        val halfTan = tan(toRad(input.verticalFovDeg) / 2f)
        val elev = toRad(pitchDeg) + atan((0.5f - hipY) * 2f * halfTan)
        // distance in body heights = 1 / (heightFrac * 2 * tan(vfov/2)); offset = tan(elev) * distance.
        val offset = tan(elev) / (heightFrac * 2f * halfTan)
        // offset > 0: hips are above the camera axis, so the camera is too low.
        val reliable = !cropped
        put(WarningCode.CameraLow, reliable && offset > config.cameraHeightEnter, reliable && offset > config.cameraHeightHold)
        put(WarningCode.CameraHigh, reliable && offset < -config.cameraHeightEnter, reliable && offset < -config.cameraHeightHold)

        // ---- Motion blur proxy: limb speed in body heights per second.
        val prev = prevLandmarks
        if (prev != null && dt > 0) {
            var fastest = 0f
            for (i in Lm.LIMB_TIPS) {
                val a = prev[i]; val b = lm[i]
                fastest = maxOf(fastest, hypot(b.x - a.x, b.y - a.y) / bodyH0 / (dt / 1000f))
            }
            speedEma = smooth(speedEma, fastest, dt, 250f)
        }
        prevLandmarks = lm
        val dim = (lumaEma ?: input.meanLuma) < config.blurLumaMax
        put(WarningCode.MotionBlur, dim && speedEma > config.blurLimbSpeedEnter, dim && speedEma > config.blurLimbSpeedHold)

        return out
    }

    /** Ends the take and produces its verdict from the frames seen since [startTake]. */
    fun finishTake(): TakeSummary {
        collecting = false
        val n = frames
        fun frac(c: WarningCode) = if (n == 0) 0f else counts[c.ordinal].toFloat() / n

        val warnings = WarningCode.entries.mapNotNull { c ->
            val f = frac(c)
            val min = if (c.severity == Severity.Block) config.reportBlockFraction else config.reportWarnFraction
            if (n > 0 && f >= min) TakeWarning(c, f, c.message, c.suggestion) else null
        }

        val reasons = ArrayList<String>()
        if (n < config.minAnalyzedFrames) {
            reasons += "Too few frames could be checked, so the take cannot be judged."
        }
        if (frac(WarningCode.BodyCropped) > config.poorCroppedFraction) {
            reasons += "Your head or feet left the frame in ${pct(frac(WarningCode.BodyCropped))} of the take."
        }
        if (frac(WarningCode.NoPerson) >= config.poorNoPersonFraction) {
            reasons += "You were not visible for ${pct(frac(WarningCode.NoPerson))} of the take."
        }
        if (frac(WarningCode.MultiplePeople) >= config.poorMultiplePeopleFraction) {
            reasons += "More than one person was in view for ${pct(frac(WarningCode.MultiplePeople))} of the take."
        }
        if (frac(WarningCode.CameraMoving) >= config.poorCameraMovingFraction) {
            reasons += "The phone was moving for ${pct(frac(WarningCode.CameraMoving))} of the take."
        }
        if (frac(WarningCode.LowLight) >= config.poorLightAndContrastFraction &&
            frac(WarningCode.LowContrast) >= config.poorLightAndContrastFraction
        ) {
            reasons += "The room is dark and your clothes blend into the background."
        }
        if (frac(WarningCode.LowLight) >= config.poorLowLightWithBlurFraction &&
            frac(WarningCode.MotionBlur) >= config.poorBlurFraction
        ) {
            reasons += "It is dark and fast movement is blurry."
        }
        if (frac(WarningCode.TooFar) >= config.poorFramingFraction) {
            reasons += "You are too far from the phone, so details are lost."
        }
        if (frac(WarningCode.TooClose) >= config.poorFramingFraction) {
            reasons += "You are too close to the phone."
        }

        val verdict = when {
            reasons.isNotEmpty() -> Verdict.Poor
            warnings.isNotEmpty() -> Verdict.Warn
            else -> Verdict.Ok
        }
        val headline = when (verdict) {
            Verdict.Ok -> "Looks good."
            Verdict.Warn -> "Usable, but check: " + warnings.joinToString("; ") { it.message.lowercase() } + "."
            Verdict.Poor -> "This footage may give bad results. " + reasons.joinToString(" ") + " Consider retaking it."
        }
        return TakeSummary(verdict, n, warnings, headline, if (verdict == Verdict.Poor) reasons else emptyList())
    }

    // ---- helpers ----

    private fun smooth(prev: Float?, value: Float, dtMs: Long, tauMs: Float): Float {
        if (prev == null || dtMs <= 0) return prev ?: value
        val alpha = 1f - exp(-dtMs / tauMs)
        return prev + alpha * (value - prev)
    }

    private inline fun List<Landmark>.minOfIndexed(ids: IntArray, f: (Landmark) -> Float): Float =
        ids.minOf { f(this[it]) }

    private inline fun List<Landmark>.maxOfIndexed(ids: IntArray, f: (Landmark) -> Float): Float =
        ids.maxOf { f(this[it]) }

    private inline fun List<Landmark>.meanOfIndexed(ids: IntArray, f: (Landmark) -> Float): Float =
        ids.map { f(this[it]) }.average().toFloat()

    private fun toDeg(rad: Float) = rad * (180f / PI.toFloat())
    private fun toRad(deg: Float) = deg * (PI.toFloat() / 180f)
    private fun pct(f: Float) = "${(f * 100f + 0.5f).toInt()}%"
}

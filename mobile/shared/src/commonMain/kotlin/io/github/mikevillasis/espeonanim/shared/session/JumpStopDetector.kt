package io.github.mikevillasis.espeonanim.shared.session

import io.github.mikevillasis.espeonanim.shared.guidance.Landmark
import io.github.mikevillasis.espeonanim.shared.guidance.Lm

/**
 * Thresholds of [JumpStopDetector]. All vertical distances are fractions of the performer's
 * standing leg length (hip to ankle, measured in the baseline), so they do not depend on how far
 * the phone is from the performer.
 */
data class JumpStopConfig(
    /** Standing baseline is learned from the first this-many ms of the recording... */
    val baselineMs: Long = 1_500,
    /** ... and needs at least this many valid frames (the window is extended until reached). */
    val baselineMinFrames: Int = 8,
    /** A landmark counts only when its visibility is at least this. */
    val minVisibility: Float = 0.5f,
    /** Baseline leg length below this (normalized image height) means a bad measurement. */
    val minLegLength: Float = 0.08f,
    /** Takeoff: ankles at least this far above the baseline ... */
    val takeoffAnkleRise: Float = 0.12f,
    /** ... and hips at least this far above the baseline. */
    val takeoffHipRise: Float = 0.05f,
    /** Consecutive valid frames that must satisfy the takeoff test (rejects single-frame glitches). */
    val takeoffFrames: Int = 2,
    /** Landing: ankles back within this of the baseline ... */
    val landedAnkleRise: Float = 0.06f,
    /** ... for this many consecutive valid frames. */
    val landingFrames: Int = 2,
    /** After landing, a pose move larger than this (ankles or hips) restarts the stability timer. */
    val settleTolerance: Float = 0.05f,
    /** Stop once the performer has been stable this long after landing. */
    val stableMs: Long = 1_500,
    /** Stop at the latest this long after landing, even if the performer keeps moving. */
    val maxAfterLandingMs: Long = 2_500,
)

enum class JumpPhase { Baseline, Waiting, Airborne, Landed, Done }

/**
 * Decides when a jump take can stop: learns the standing pose, detects takeoff and landing from
 * ankle/hip height, then requests a stop once the performer has settled. Pure logic, fed one
 * pose frame at a time; cost per frame is a few comparisons. Only the first jump counts, so
 * later jumps never extend the take beyond [JumpStopConfig.maxAfterLandingMs] after landing.
 *
 * Call [reset] when a recording starts. If no jump is detected, [update] never returns true and
 * the session's fixed time limit applies.
 */
class JumpStopDetector(private val config: JumpStopConfig = JumpStopConfig()) {
    var phase: JumpPhase = JumpPhase.Baseline
        private set

    private var startMs = -1L
    private val ankleSamples = ArrayList<Float>()
    private val hipSamples = ArrayList<Float>()
    private var baseAnkle = 0f
    private var baseHip = 0f
    private var leg = 0f

    private var takeoffCount = 0
    private var landCount = 0
    private var landingMs = 0L
    private var segmentStartMs = 0L
    private var refAnkle = 0f
    private var refHip = 0f

    fun reset() {
        phase = JumpPhase.Baseline
        startMs = -1L
        ankleSamples.clear(); hipSamples.clear()
        takeoffCount = 0; landCount = 0
    }

    /**
     * Feed one analyzed frame. [landmarks] is the 33-point pose of the performer, or empty when
     * nobody was detected. Returns true once the take should stop (and on every later call).
     */
    fun update(timestampMs: Long, landmarks: List<Landmark>): Boolean {
        if (phase == JumpPhase.Done) return true
        if (startMs < 0) startMs = timestampMs
        val ankleY = meanY(landmarks, Lm.LEFT_ANKLE, Lm.RIGHT_ANKLE)
        val hipY = meanY(landmarks, Lm.LEFT_HIP, Lm.RIGHT_HIP)
        val valid = !ankleY.isNaN() && !hipY.isNaN()

        when (phase) {
            JumpPhase.Baseline -> {
                if (valid) { ankleSamples += ankleY; hipSamples += hipY }
                if (timestampMs - startMs >= config.baselineMs && ankleSamples.size >= config.baselineMinFrames) {
                    val a = median(ankleSamples)
                    val h = median(hipSamples)
                    if (a - h >= config.minLegLength) {
                        baseAnkle = a; baseHip = h; leg = a - h
                        phase = JumpPhase.Waiting
                    } else {
                        // Legs not (fully) in view: keep the most recent samples and try again.
                        if (ankleSamples.size > 45) { ankleSamples.removeAt(0); hipSamples.removeAt(0) }
                    }
                }
            }
            JumpPhase.Waiting -> if (valid) {
                val up = (baseAnkle - ankleY) / leg >= config.takeoffAnkleRise &&
                    (baseHip - hipY) / leg >= config.takeoffHipRise
                takeoffCount = if (up) takeoffCount + 1 else 0
                if (takeoffCount >= config.takeoffFrames) { phase = JumpPhase.Airborne; landCount = 0 }
            }
            JumpPhase.Airborne -> if (valid) {
                val down = (baseAnkle - ankleY) / leg < config.landedAnkleRise
                landCount = if (down) landCount + 1 else 0
                if (landCount >= config.landingFrames) {
                    phase = JumpPhase.Landed
                    landingMs = timestampMs
                    segmentStartMs = timestampMs
                    refAnkle = ankleY; refHip = hipY
                }
            }
            JumpPhase.Landed -> {
                if (valid) {
                    val tol = config.settleTolerance * leg
                    if (kotlin.math.abs(ankleY - refAnkle) > tol || kotlin.math.abs(hipY - refHip) > tol) {
                        segmentStartMs = timestampMs
                        refAnkle = ankleY; refHip = hipY
                    }
                }
                if (timestampMs - segmentStartMs >= config.stableMs ||
                    timestampMs - landingMs >= config.maxAfterLandingMs
                ) phase = JumpPhase.Done
            }
            JumpPhase.Done -> Unit
        }
        return phase == JumpPhase.Done
    }

    /** Mean y of the visible landmarks among [a] and [b]; NaN when none is visible. */
    private fun meanY(lms: List<Landmark>, a: Int, b: Int): Float {
        var sum = 0f
        var n = 0
        for (i in intArrayOf(a, b)) {
            val l = lms.getOrNull(i) ?: continue
            if (l.visibility >= config.minVisibility) { sum += l.y; n++ }
        }
        return if (n == 0) Float.NaN else sum / n
    }

    private fun median(v: List<Float>): Float = v.sorted().let { it[it.size / 2] }
}

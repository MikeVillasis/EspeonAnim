package io.github.mikevillasis.espeonanim.shared

import io.github.mikevillasis.espeonanim.shared.guidance.Landmark
import io.github.mikevillasis.espeonanim.shared.guidance.Lm
import io.github.mikevillasis.espeonanim.shared.session.JumpPhase
import io.github.mikevillasis.espeonanim.shared.session.JumpStopDetector
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JumpStopDetectorTest {
    private val hipY = 0.50f
    private val ankleY = 0.85f // leg length 0.35

    private fun pose(lift: Float = 0f, hipDrop: Float = 0f, noise: Float = 0f, vis: Float = 1f): List<Landmark> {
        val lms = MutableList(33) { Landmark(0.5f, 0.5f, vis) }
        lms[Lm.LEFT_HIP] = Landmark(0.45f, hipY - lift + hipDrop + noise, vis)
        lms[Lm.RIGHT_HIP] = Landmark(0.55f, hipY - lift + hipDrop - noise, vis)
        lms[Lm.LEFT_ANKLE] = Landmark(0.45f, ankleY - lift + noise, vis)
        lms[Lm.RIGHT_ANKLE] = Landmark(0.55f, ankleY - lift - noise, vis)
        return lms
    }

    /** A tiny deterministic jitter, +/- [amp]. */
    private fun jitter(i: Int, amp: Float) = ((i * 7919) % 11 - 5) / 5f * amp

    /** Feeds frames at ~15 fps, tracking the clock and the first stop request. */
    private class Run(val d: JumpStopDetector) {
        var t = 0L
        var stopAt: Long? = null
        var i = 0
        fun feed(lms: List<Landmark>): Boolean {
            val r = d.update(t, lms)
            if (r && stopAt == null) stopAt = t
            t += 67
            return r
        }
    }

    private fun Run.stand(untilMs: Long) {
        while (t < untilMs) feed(poseStill(i++))
    }

    private fun poseStill(i: Int) = pose(noise = jitter(i, 0.004f))

    private fun Run.airborne(peak: Float = 0.12f) {
        val n = 9 // ~0.6 s airtime
        for (k in 0 until n) feed(pose(lift = peak * sin(PI.toFloat() * (k + 0.5f) / n)))
    }

    @Test
    fun cleanJumpStopsAboutOneAndHalfSecondsAfterLanding() {
        val run = Run(JumpStopDetector())
        run.stand(2_500)
        repeat(3) { run.feed(pose(hipDrop = 0.04f)) } // crouch
        run.airborne()
        val landing = run.t
        run.stand(landing + 4_000)
        val stop = assertNotNull(run.stopAt, "should request stop")
        val dt = stop - landing
        assertTrue(dt in 1_300..2_000, "stop $dt ms after landing")
        assertEquals(JumpPhase.Done, run.d.phase)
    }

    @Test
    fun noJumpNeverStops() {
        val run = Run(JumpStopDetector())
        while (run.t < 20_000) assertFalse(run.feed(pose(noise = jitter(run.i++, 0.006f))))
        assertEquals(JumpPhase.Waiting, run.d.phase)
        assertNull(run.stopAt)
    }

    @Test
    fun crouchAndSmallBobDoNotCountAsTakeoff() {
        val run = Run(JumpStopDetector())
        run.stand(2_000)
        repeat(15) { assertFalse(run.feed(pose(hipDrop = 0.10f))) } // deep squat: hips drop, ankles stay
        repeat(30) { assertFalse(run.feed(pose(lift = 0.03f))) } // body bob under 10% of leg length
        assertEquals(JumpPhase.Waiting, run.d.phase)
    }

    @Test
    fun missingAndNoisyFramesGiveNoFalseTakeoff() {
        val run = Run(JumpStopDetector())
        run.stand(2_000)
        repeat(20) { assertFalse(run.feed(emptyList())) } // person lost
        repeat(20) { assertFalse(run.feed(pose(lift = 0.3f, vis = 0.1f))) } // garbage with low visibility
        repeat(10) { // isolated single-frame spikes
            assertFalse(run.feed(pose(lift = 0.25f)))
            repeat(3) { assertFalse(run.feed(pose())) }
        }
        assertEquals(JumpPhase.Waiting, run.d.phase)

        // Frames without a person during the baseline window do not break learning.
        val r2 = Run(JumpStopDetector())
        repeat(10) { r2.feed(emptyList()) }
        while (r2.t < 4_000) r2.feed(pose())
        assertEquals(JumpPhase.Waiting, r2.d.phase)
    }

    @Test
    fun dropoutsAroundTheJumpStillDetectAndStop() {
        val run = Run(JumpStopDetector())
        run.stand(2_000)
        repeat(2) { run.feed(pose(lift = 0.12f)) }
        repeat(3) { run.feed(emptyList()) } // dropout mid-air
        repeat(3) { run.feed(pose(lift = 0.12f)) }
        val landing = run.t
        while (run.t < landing + 4_000) run.feed(if (run.i++ % 4 == 0) emptyList() else poseStill(run.i))
        val stop = assertNotNull(run.stopAt)
        assertTrue(stop - landing in 1_300..2_600, "stop ${stop - landing}")
    }

    @Test
    fun repeatedJumpsStopAtMostTwoAndHalfSecondsAfterFirstLanding() {
        val run = Run(JumpStopDetector())
        run.stand(2_000)
        run.airborne()
        val landing = run.t
        while (run.t < landing + 600) run.feed(pose()) // second jump 0.6 s after first landing
        run.airborne()
        while (run.t < landing + 1_300 + 600) run.feed(pose()) // third jump
        run.airborne()
        while (run.t < landing + 6_000) run.feed(pose())
        val stop = assertNotNull(run.stopAt)
        val dt = stop - landing
        assertTrue(dt in 2_400..2_600, "should stop at the 2.5 s cap after the first landing, was $dt")
    }

    @Test
    fun resetAllowsAnotherTake() {
        val d = JumpStopDetector()
        val r1 = Run(d)
        r1.stand(2_500)
        r1.airborne()
        r1.stand(r1.t + 3_000)
        assertNotNull(r1.stopAt)
        d.reset()
        assertEquals(JumpPhase.Baseline, d.phase)
        val r2 = Run(d)
        while (r2.t < 5_000) assertFalse(r2.feed(poseStill(r2.i++)))
    }
}

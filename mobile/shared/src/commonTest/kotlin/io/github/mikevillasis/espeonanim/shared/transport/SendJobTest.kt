package io.github.mikevillasis.espeonanim.shared.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SendJobTest {
    private val pairing = PairingInfo("192.168.1.5", 8765, "0123456789abcdef0123456789abcdef", "Desk PC")
    private val job = PersistedJob(pairing.toUri(), "/files/x.espeon")

    private fun status(state: JobState, quality: String = "", warnings: List<JobWarning> = emptyList(), msg: String = "") =
        JobStatus("j1", state, 1f, msg, quality, warnings)

    @Test fun noRecordMeansNothing() = assertEquals(ResumeDecision.Nothing, SendJobLogic.decide(null))

    @Test fun uploadedJobResumesPollingWithoutUploading() {
        val d = SendJobLogic.decide(job.withJobId("j1"))
        assertIs<ResumeDecision.Poll>(d)
        assertEquals("j1", d.jobId)
        assertEquals("Desk PC", d.pairing.pcName)
    }

    @Test fun interruptedUploadIsReportedNotRetried() {
        val d = SendJobLogic.decide(job)
        assertIs<ResumeDecision.Show>(d)
        assertIs<SendState.Failed>(d.state)
    }

    @Test fun brokenPairingNeedsRescan() {
        val d = SendJobLogic.decide(job.copy(pairingUri = "junk").withJobId("j1"))
        assertIs<ResumeDecision.Show>(d)
        assertTrue((d.state as SendState.Failed).needsRescan)
    }

    @Test fun storedOutcomeIsShownNotPolled() {
        val w = JobWarning("w", "msg", "Hold still")
        val done = job.withJobId("j1").withOutcome(SendState.Done(status(JobState.Done, "good", listOf(w))))
        val d = SendJobLogic.decide(done)
        assertIs<ResumeDecision.Show>(d)
        val s = d.state
        assertIs<SendState.Done>(s)
        assertEquals("good", s.status.quality)
        assertEquals(listOf(w), s.status.warnings)

        val failed = job.withJobId("j1").withOutcome(SendState.Failed("nope", true))
        val f = (SendJobLogic.decide(failed) as ResumeDecision.Show).state as SendState.Failed
        assertEquals("nope", f.message)
        assertTrue(f.needsRescan)
    }

    @Test fun nonTerminalStateDoesNotRecordOutcome() {
        assertNull(job.withOutcome(SendState.Processing(null)).outcome)
    }

    @Test fun encodeDecodeRoundTripAndGarbage() {
        val j = job.withJobId("j1").withOutcome(SendState.Failed("x", false))
        assertEquals(j, PersistedJob.decode(j.encode()))
        assertNull(PersistedJob.decode("not json"))
        val store = MemoryJobStore()
        store.save(j)
        assertNotNull(store.load())
        store.clear()
        assertNull(store.load())
    }

    @Test fun notificationTexts() {
        val up = SendNotifications.contentFor(SendState.Uploading(0.42f), "Desk PC")!!
        assertEquals("Uploading 42%", up.text)
        assertEquals(42, up.progress)
        assertTrue(up.ongoing)
        val pr = SendNotifications.contentFor(SendState.Processing(status(JobState.Processing, msg = "Solving")), "Desk PC")!!
        assertEquals("Processing on Desk PC: Solving", pr.text)
        assertEquals("Stop watching", pr.cancelLabel)
        val waiting = SendNotifications.contentFor(SendState.Processing(null), "Desk PC")!!
        assertNull(waiting.progress)
        val done = SendNotifications.contentFor(
            SendState.Done(status(JobState.Done, "usable", listOf(JobWarning("a", "m", "Light the room")))), "Desk PC",
        )!!
        assertEquals("Quality: usable. Tip: Light the room", done.text)
        assertTrue(!done.ongoing)
        val bad = SendNotifications.contentFor(SendState.Failed("Cannot reach your PC.", false), "Desk PC")!!
        assertEquals("Cannot reach your PC.", bad.text)
        assertNull(SendNotifications.contentFor(SendState.Idle, "x"))
        assertNull(SendNotifications.contentFor(SendState.Cancelled, "x"))
    }
}

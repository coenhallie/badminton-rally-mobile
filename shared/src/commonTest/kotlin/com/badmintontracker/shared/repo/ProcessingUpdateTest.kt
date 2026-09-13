package com.badmintontracker.shared.repo

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Which cloud statuses mean which kind of "done".
 *
 * There are two kinds now and conflating them is the bug this guards. Phase 1
 * ends with watchable clips and the pipeline STOPS: nothing runs Phase 2
 * unless someone calls start-analytics. Phase 2 ends with the pose artifact.
 * A wait for the first that kept waiting would hang on every video nobody
 * asked for analytics on; a wait for the second that stopped at the first
 * would fetch an artifact that does not exist yet.
 */
class ProcessingUpdateTest {

    private fun update(status: String) = ProcessingUpdate(status, progress = null, error = null)

    @Test
    fun phase_one_completion_means_clips_but_not_analytics() {
        val u = update(VideoStatus.PHASE1_COMPLETE)
        u.hasClips shouldBe true
        u.hasAnalytics shouldBe false
    }

    @Test
    fun completion_means_both() {
        // Phase 2 merges into Phase 1's results rather than replacing them,
        // so a completed video still has its clips.
        val u = update(VideoStatus.COMPLETED)
        u.hasClips shouldBe true
        u.hasAnalytics shouldBe true
    }

    @Test
    fun a_clip_wait_ends_at_phase_one_because_nothing_advances_on_its_own() {
        update(VideoStatus.PHASE1_COMPLETE).isTerminal shouldBe true
    }

    @Test
    fun an_analytics_wait_does_not_end_at_phase_one() {
        // The concrete failure this prevents: an app that triggered analytics
        // and then stopped observing at phase1_complete would report success
        // and fetch a poses.raw that Modal has not written yet.
        update(VideoStatus.PHASE1_COMPLETE).isAnalyticsTerminal shouldBe false
        update(VideoStatus.PROCESSING_PHASE2).isAnalyticsTerminal shouldBe false
        update(VideoStatus.COMPLETED).isAnalyticsTerminal shouldBe true
    }

    @Test
    fun a_phase_two_failure_ends_both_kinds_of_wait() {
        // failed_phase2 is a real status the edge function rolls back to, and
        // a wait that did not recognise it would poll until the app died.
        val u = update("failed_phase2")
        u.isFailure shouldBe true
        u.isTerminal shouldBe true
        u.isAnalyticsTerminal shouldBe true
        u.hasAnalytics shouldBe false
    }

    @Test
    fun a_phase_one_failure_is_still_a_failure() {
        update("failed_phase1").isFailure shouldBe true
    }

    @Test
    fun a_status_nobody_recognises_is_neither_done_nor_failed() {
        // A status added server-side that this build has never heard of must
        // keep the wait running, not end it as a success.
        val u = update("processing_phase3")
        u.hasClips shouldBe false
        u.isFailure shouldBe false
        u.isTerminal shouldBe false
        u.isAnalyticsTerminal shouldBe false
    }
}

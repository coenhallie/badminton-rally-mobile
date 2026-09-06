package com.badmintontracker.analysis.corpus

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Parser coverage, using the hand-authored `synthetic` fixture.
 *
 * This deliberately does NOT use the captured corpus. What it checks is that
 * the key names, nesting and null handling in the loader are right, which is
 * the part that can be wrong regardless of where the data came from. Whether
 * the pipeline agrees with the cloud is Phase1PipelineTest's job, and that
 * needs real recorded output rather than numbers this repo invented.
 */
class CorpusFixtureTest {

    @Test
    fun the_fixture_loads_with_a_usable_frame_rate_and_shuttle_track() = withCorpus("synthetic") { e ->
        e.fps shouldBe 30.0
        e.totalFrames shouldBe 600
        e.videoWidth shouldBe 1920
        e.videoHeight shouldBe 1080
        e.shuttlePositions.size shouldBe 600
        e.shuttlePositions.values.count { it.visible } shouldBe 288
        e.cloudRallies.map { it.id } shouldBe listOf(1, 2)
        e.cloudClips.map { it.rallyIndex } shouldBe listOf(1, 2)
    }

    @Test
    fun the_fixture_carries_the_twelve_court_keypoints() = withCorpus("synthetic") { e ->
        // Without keypoints the ROI filter is skipped, and the comparison
        // would measure something different from what the cloud actually ran.
        // CourtKeypoints.fromMap returns null unless all twelve are present,
        // so a non-null result is the assertion that all twelve survived.
        val keypoints = e.keypoints
        (keypoints != null) shouldBe true
        keypoints!!.topLeft.x shouldBe 200.0
        keypoints.centerFar.y shouldBe 670.0
    }

    @Test
    fun a_missing_fixture_is_null_rather_than_an_exception() {
        // This is what lets the golden test skip loudly instead of failing a
        // checkout that has no captured corpus.
        loadCorpusEntryOrNull("no-such-fixture") shouldBe null
    }
}

package com.badmintontracker.shared.local

import com.badmintontracker.analysis.raw.RawHeader
import com.badmintontracker.analysis.raw.RawInference
import com.badmintontracker.analysis.raw.RawInferenceCodec
import com.badmintontracker.shared.model.CourtKeypoints
import com.badmintontracker.shared.repo.CloudPoseArtifact
import com.badmintontracker.shared.repo.CloudPoseRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Fetching one video's cloud pose artifact and turning it into a stored
 * analysis.
 *
 * The whole path runs in CI against a fake with no phone and no Supabase,
 * which is the point of the repository being an interface: the sequencing is
 * what goes wrong here, not the HTTP.
 */
class CloudPoseCoordinatorTest {

    private class FakeRepository(
        private val artifact: CloudPoseArtifact? = null,
        private val bytes: ByteArray? = null,
        private val artifactError: Throwable? = null,
        private val downloadError: Throwable? = null,
    ) : CloudPoseRepository {
        var downloads = 0

        override suspend fun artifact(videoId: String): Result<CloudPoseArtifact?> =
            artifactError?.let { Result.failure(it) } ?: Result.success(artifact)

        override suspend fun download(
            artifact: CloudPoseArtifact,
            onProgress: (Float) -> Unit,
        ): Result<ByteArray> {
            downloads++
            onProgress(0.5f)
            return downloadError?.let { Result.failure(it) } ?: Result.success(bytes!!)
        }
    }

    // The same wire marks CloudPoseTest uses, so the two files cannot
    // disagree about what a usable court looks like.
    private val marks = CourtKeypoints(
        topLeft = listOf(200f, 200f), topRight = listOf(1700f, 200f),
        bottomRight = listOf(1700f, 900f), bottomLeft = listOf(200f, 900f),
        netLeft = listOf(200f, 550f), netRight = listOf(1700f, 550f),
        serviceLineNearLeft = listOf(200f, 430f), serviceLineNearRight = listOf(1700f, 430f),
        serviceLineFarLeft = listOf(200f, 670f), serviceLineFarRight = listOf(1700f, 670f),
        centerNear = listOf(950f, 430f), centerFar = listOf(950f, 670f),
    )

    private fun streamOf(frames: Int): ByteArray = RawInferenceCodec.encode(
        RawInference(
            header = RawHeader(1, 30.0, frames, 1920, 1080, "cloud:test"),
            frames = emptyList(),
        ),
    )

    @Test
    fun a_video_the_cloud_has_no_artifact_for_installs_nothing() = runTest {
        // The ordinary case for every video analysed before this shipped, and
        // for every video whose Phase 2 artifact upload failed - which is
        // non-fatal on the worker, so results_meta carries a null path.
        val repository = FakeRepository(artifact = null)
        var saved = 0

        val result = CloudPoseCoordinator(repository).install("v1") { saved++ }

        result.getOrNull() shouldBe false
        saved shouldBe 0
        repository.downloads shouldBe 0
    }

    @Test
    fun an_artifact_is_downloaded_decoded_and_handed_to_the_sink_once() = runTest {
        val repository = FakeRepository(
            artifact = CloudPoseArtifact("v1", "uid/v1/poses.raw", frameCount = 0, keypoints = marks),
            bytes = streamOf(frames = 0),
        )
        var saved: CloudPoseOutcome? = null

        val result = CloudPoseCoordinator(repository).install("v1") { saved = it }

        result.getOrNull() shouldBe true
        repository.downloads shouldBe 1
        saved!!.videoWidth shouldBe 1920
        saved!!.fps shouldBe 30.0
        // Both sides always, even from an empty stream: a panel needs to tell
        // "nobody was found" from "this build predates the far player".
        saved!!.selections.size shouldBe 2
    }

    @Test
    fun progress_from_the_download_reaches_the_caller() = runTest {
        val repository = FakeRepository(
            artifact = CloudPoseArtifact("v1", "uid/v1/poses.raw", 0, marks),
            bytes = streamOf(0),
        )
        val seen = mutableListOf<Float>()

        CloudPoseCoordinator(repository).install("v1", onProgress = { seen.add(it) }) {}

        seen.contains(0.5f) shouldBe true
        // Completion is the coordinator's to report, after the decode and the
        // selection rather than when the bytes land. Same rule
        // LocalAnalysisCoordinator applies to its engine.
        seen.last() shouldBe 1f
    }

    @Test
    fun an_artifact_the_row_has_no_marks_for_installs_nothing() = runTest {
        // The repository returns null in that case rather than an artifact
        // with no court, so this is the same path as "no artifact". Pinned
        // separately because the two have different causes and a future edit
        // might reasonably want to tell them apart.
        val repository = FakeRepository(artifact = null)

        CloudPoseCoordinator(repository).install("v1") {}.getOrNull() shouldBe false
    }

    @Test
    fun a_failed_lookup_is_a_failure_the_caller_can_render_not_a_crash() = runTest {
        val repository = FakeRepository(artifactError = IllegalStateException("HTTP 503"))
        var saved = 0

        val result = CloudPoseCoordinator(repository).install("v1") { saved++ }

        result.isFailure shouldBe true
        saved shouldBe 0
    }

    @Test
    fun a_failed_download_saves_nothing() = runTest {
        // Half a stream must never reach a store. The stores write
        // atomically, so a partial file cannot become visible, but a partial
        // DECODE would throw mid-selection and the sink must not have been
        // called with whatever had accumulated.
        val repository = FakeRepository(
            artifact = CloudPoseArtifact("v1", "uid/v1/poses.raw", 0, marks),
            downloadError = IllegalStateException("connection lost"),
        )
        var saved = 0

        val result = CloudPoseCoordinator(repository).install("v1") { saved++ }

        result.isFailure shouldBe true
        saved shouldBe 0
    }

    @Test
    fun a_corrupt_stream_is_a_failure_rather_than_an_empty_analysis() = runTest {
        // RawInferenceCodec throws on a bad magic or a truncated stream, on
        // purpose: "decoding a half-written file into a plausible shorter
        // video would move every rally boundary with nothing reporting why".
        // That exception must surface as a failed install, not as a video
        // that quietly has no heatmap.
        val repository = FakeRepository(
            artifact = CloudPoseArtifact("v1", "uid/v1/poses.raw", 0, marks),
            bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
        )
        var saved = 0

        val result = CloudPoseCoordinator(repository).install("v1") { saved++ }

        result.isFailure shouldBe true
        saved shouldBe 0
    }
}

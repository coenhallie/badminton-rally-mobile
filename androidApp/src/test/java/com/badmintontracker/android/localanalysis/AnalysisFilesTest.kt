package com.badmintontracker.android.localanalysis

import com.badmintontracker.shared.localvideo.LocalVideoRemoval
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A removed match must not keep its analysis. The clips alone are tens of
 * megabytes of re-encoded video, and once the entry is gone nothing in the app
 * can reach them again.
 */
class AnalysisFilesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun write(path: String): File =
        File(temp.root, path).apply { parentFile?.mkdirs(); writeText("x") }

    @Test
    fun `deletes both shapes a run writes`() {
        val track = write("player-tracks/e1.track")
        val skeleton = write("skeletons/e1.skel")
        val clip = write("local-clips/e1/rally-1.mp4")
        val index = write("local-clips/e1/clips.index")
        val source = write("local-sources/e1.mp4")

        AnalysisFiles.deleteAll(temp.root, "e1", LocalVideoRemoval.DISCARDED)

        listOf(track, skeleton, clip, index, source).map { it.exists() } shouldBe
            List(5) { false }
    }

    @Test
    fun `deletes the cloud artifacts for a discarded entry too`() {
        // A coach deleting a video wants everything derived from it gone, and
        // a cloud artifact costs a re-download rather than half an hour of
        // device time, so nothing argues for keeping it past the video.
        val cloudTrack = write("cloud-tracks/e1.track")
        val cloudSkeleton = write("cloud-skeletons/e1.skel")

        AnalysisFiles.deleteAll(temp.root, "e1", LocalVideoRemoval.DISCARDED)

        listOf(cloudTrack, cloudSkeleton).map { it.exists() } shouldBe List(2) { false }
    }

    @Test
    fun `keeps the cloud artifacts for a retired entry and drops the local ones`() {
        // The pipeline tidying up after its own success. The cloud track and
        // skeleton ARE that run's result, written moments earlier; taking them
        // here threw away the heatmap the coach waited on a GPU for.
        val cloudTrack = write("cloud-tracks/e1.track")
        val cloudSkeleton = write("cloud-skeletons/e1.skel")
        val localTrack = write("player-tracks/e1.track")
        val localClip = write("local-clips/e1/rally-1.mp4")
        val source = write("local-sources/e1.mp4")

        AnalysisFiles.deleteAll(temp.root, "e1", LocalVideoRemoval.RETIRED)

        listOf(cloudTrack, cloudSkeleton).map { it.exists() } shouldBe List(2) { true }
        listOf(localTrack, localClip, source).map { it.exists() } shouldBe List(3) { false }
    }

    @Test
    fun `leaves another video's analysis alone`() {
        // Prefix matching would take these with it: the ids are opaque strings
        // and nothing stops one from starting with another.
        val other = write("player-tracks/e10.track")
        val otherClip = write("local-clips/e10/rally-1.mp4")
        write("player-tracks/e1.track")

        AnalysisFiles.deleteAll(temp.root, "e1", LocalVideoRemoval.DISCARDED)

        other.exists() shouldBe true
        otherClip.exists() shouldBe true
    }

    @Test
    fun `a video that was never analysed is not an error`() {
        AnalysisFiles.deleteAll(temp.root, "never-run", LocalVideoRemoval.DISCARDED)
    }
}

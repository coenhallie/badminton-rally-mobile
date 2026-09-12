package com.badmintontracker.android.localanalysis

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

        AnalysisFiles.deleteAll(temp.root, "e1")

        listOf(track, skeleton, clip, index, source).map { it.exists() } shouldBe
            List(5) { false }
    }

    @Test
    fun `deletes the cloud artifacts for a removed entry too`() {
        // Removing an entry already removes its derived local analysis; a
        // cloud artifact is not protected the way a local one is, since it
        // costs a re-download rather than half an hour of device time, so the
        // same precedent applies to it.
        val cloudTrack = write("cloud-tracks/e1.track")
        val cloudSkeleton = write("cloud-skeletons/e1.skel")

        AnalysisFiles.deleteAll(temp.root, "e1")

        listOf(cloudTrack, cloudSkeleton).map { it.exists() } shouldBe List(2) { false }
    }

    @Test
    fun `leaves another video's analysis alone`() {
        // Prefix matching would take these with it: the ids are opaque strings
        // and nothing stops one from starting with another.
        val other = write("player-tracks/e10.track")
        val otherClip = write("local-clips/e10/rally-1.mp4")
        write("player-tracks/e1.track")

        AnalysisFiles.deleteAll(temp.root, "e1")

        other.exists() shouldBe true
        otherClip.exists() shouldBe true
    }

    @Test
    fun `a video that was never analysed is not an error`() {
        AnalysisFiles.deleteAll(temp.root, "never-run")
    }
}

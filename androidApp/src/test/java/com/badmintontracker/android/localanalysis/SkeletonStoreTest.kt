package com.badmintontracker.android.localanalysis

import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.player.Coco
import com.badmintontracker.analysis.player.PlayerPose
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The per-run pose file: written once after a run, read back on the detail
 * screen, and refused when it is not something this app can draw.
 */
class SkeletonStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = SkeletonStore(temp.root)

    private fun pose(frame: Int) = PlayerPose(
        frame = frame,
        timestamp = frame / 25.0,
        keypoints = List(Coco.COUNT) { Point(frame + it * 1.5, frame * 2.0 + it) },
        confidence = List(Coco.COUNT) { 0.5f + it / 100f },
    )

    @Test
    fun a_saved_skeleton_reads_back_exactly() {
        val s = store()
        val poses = listOf(pose(0), pose(1), pose(3))
        s.save("e1", poses, fps = 25.0, videoWidth = 1920, videoHeight = 1080)
        val stored = s.load("e1")!!
        stored.fps shouldBe 25.0
        stored.videoWidth shouldBe 1920
        stored.videoHeight shouldBe 1080
        stored.poses.size shouldBe 3
        stored.poses.map { it.frame } shouldBe listOf(0, 1, 3)
        stored.poses[2].timestamp shouldBe 3 / 25.0
        // Floats survive as floats: the file stores what the model emitted.
        stored.poses[1].keypoints[5] shouldBe Point(1 + 5 * 1.5, 2.0 + 5)
        stored.poses[1].confidence[16] shouldBe (0.5f + 16 / 100f)
    }

    @Test
    fun a_video_never_analysed_for_skeletons_has_none() {
        store().has("e1") shouldBe false
        store().load("e1") shouldBe null
    }

    @Test
    fun has_reads_only_the_header_and_agrees_with_load_on_a_healthy_file() {
        val s = store()
        s.save("e1", listOf(pose(0)), 30.0, 1280, 720)
        s.has("e1") shouldBe true
        s.has("e2") shouldBe false
        s.has("e1") shouldBe (s.load("e1") != null)
    }

    @Test
    fun another_format_version_is_refused() {
        val s = store()
        s.save("e1", listOf(pose(0)), 30.0, 1280, 720)
        val onDisk = temp.root.walkTopDown().first { it.isFile && it.name.startsWith("e1") }
        val bytes = onDisk.readBytes()
        bytes[4] = 9 // version, little-endian low byte
        onDisk.writeBytes(bytes)
        s.has("e1") shouldBe false
        s.load("e1") shouldBe null
    }

    @Test
    fun a_file_truncated_mid_pose_loads_as_nothing() {
        val s = store()
        s.save("e1", listOf(pose(0), pose(1)), 30.0, 1280, 720)
        val onDisk = temp.root.walkTopDown().first { it.isFile && it.name.startsWith("e1") }
        val bytes = onDisk.readBytes()
        onDisk.writeBytes(bytes.copyOf(bytes.size - 10))
        // A skeleton with a pose missing its last joints is not a skeleton.
        s.load("e1") shouldBe null
        // has() returns true here because the header is intact, but load() returns null:
        // the one case they disagree. This cannot arise from this store's own writes,
        // since atomicity means the file is never truncated in place.
        s.has("e1") shouldBe true
    }

    @Test
    fun saving_nothing_writes_nothing() {
        val s = store()
        s.save("e1", emptyList(), 30.0, 1280, 720)
        s.has("e1") shouldBe false
    }

    @Test
    fun save_writes_atomically_with_no_tmp_leftover() {
        val s = store()
        s.save("e1", listOf(pose(0)), 30.0, 1280, 720)
        val skeleton = temp.root.resolve("skeletons")
        skeleton.isDirectory shouldBe true
        skeleton.listFiles()!!.map { it.name } shouldBe listOf("e1.skel")
    }
}

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

    private val marks = com.badmintontracker.analysis.geometry.CourtKeypoints(
        topLeft = Point(649.5, 484.8), topRight = Point(1277.3, 481.2),
        bottomRight = Point(1579.4, 998.6), bottomLeft = Point(360.0, 1004.0),
        netLeft = Point(550.0, 663.9), netRight = Point(1382.2, 665.7),
        serviceLineNearLeft = Point(504.8, 743.5), serviceLineNearRight = Point(1422.0, 738.1),
        serviceLineFarLeft = Point(588.0, 595.2), serviceLineFarRight = Point(1338.8, 595.2),
        centerNear = Point(966.1, 593.4), centerFar = Point(966.1, 736.3),
    )

    @Test
    fun a_saved_skeleton_reads_back_exactly() {
        val s = store()
        val poses = listOf(pose(0), pose(1), pose(3))
        s.save("e1", poses, fps = 25.0, videoWidth = 1920, videoHeight = 1080, marks = null)
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
        s.save("e1", listOf(pose(0)), 30.0, 1280, 720, null)
        s.has("e1") shouldBe true
        s.has("e2") shouldBe false
        s.has("e1") shouldBe (s.load("e1") != null)
    }

    @Test
    fun another_format_version_is_refused() {
        val s = store()
        s.save("e1", listOf(pose(0)), 30.0, 1280, 720, null)
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
        s.save("e1", listOf(pose(0), pose(1)), 30.0, 1280, 720, null)
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
        s.save("e1", emptyList(), 30.0, 1280, 720, null)
        s.has("e1") shouldBe false
    }

    @Test
    fun save_writes_atomically_with_no_tmp_leftover() {
        val s = store()
        s.save("e1", listOf(pose(0)), 30.0, 1280, 720, null)
        val skeleton = temp.root.resolve("skeletons")
        skeleton.isDirectory shouldBe true
        skeleton.listFiles()!!.map { it.name } shouldBe listOf("e1.skel")
    }

    @Test
    fun saving_twice_for_the_same_entry_replaces_the_file() {
        val s = store()
        s.save("e1", listOf(pose(0), pose(1)), 30.0, 1280, 720, null)
        s.save("e1", listOf(pose(2), pose(3), pose(4)), 60.0, 640, 480, null)
        val skeleton = temp.root.resolve("skeletons")
        skeleton.listFiles()!!.map { it.name } shouldBe listOf("e1.skel")
        val loaded = s.load("e1")!!
        loaded.fps shouldBe 60.0
        loaded.videoWidth shouldBe 640
        loaded.videoHeight shouldBe 480
        loaded.poses.map { it.frame } shouldBe listOf(2, 3, 4)
    }

    @Test
    fun save_throws_and_cleans_up_on_rename_failure() {
        val s = store()
        val skeleton = temp.root.resolve("skeletons")
        skeleton.mkdirs()
        val blockingDir = skeleton.resolve("e1.skel")
        blockingDir.mkdir()
        try {
            s.save("e1", listOf(pose(0)), 30.0, 1280, 720, null)
            throw AssertionError("Expected IOException")
        } catch (e: java.io.IOException) {
            // Expected
        }
        temp.root.resolve("skeletons").listFiles()!!.map { it.name } shouldBe listOf("e1.skel")
        temp.root.resolve("skeletons").listFiles()!!.any { it.name.endsWith(".tmp") } shouldBe false
    }

    @Test
    fun delete_after_save_leaves_has_false_and_load_null() {
        val s = store()
        s.save("e1", listOf(pose(0)), 30.0, 1280, 720, null)
        s.delete("e1")
        s.has("e1") shouldBe false
        s.load("e1") shouldBe null
    }

    @Test
    fun the_court_marks_travel_with_the_poses() {
        val s = store()
        s.save("e1", listOf(pose(0), pose(1)), 30.0, 1920, 1080, marks)
        val stored = s.load("e1")!!
        stored.marks shouldBe marks
        stored.poses.size shouldBe 2
    }

    @Test
    fun a_skeleton_saved_without_marks_loads_without_them() {
        val s = store()
        s.save("e1", listOf(pose(0)), 30.0, 1920, 1080, null)
        s.load("e1")!!.marks shouldBe null
    }

    @Test
    fun a_version_one_file_still_loads_with_no_marks() {
        // Written by hand in the v1 layout: a file from before the marks existed.
        val p = pose(7)
        val buffer = java.nio.ByteBuffer.allocate(28 + 216).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.put("SKEL".toByteArray(Charsets.US_ASCII)).putInt(1).putDouble(25.0).putInt(1280).putInt(720).putInt(1)
        buffer.putInt(p.frame).putDouble(p.timestamp)
        for (k in 0 until Coco.COUNT) {
            buffer.putFloat(p.keypoints[k].x.toFloat()).putFloat(p.keypoints[k].y.toFloat()).putFloat(p.confidence[k])
        }
        val file = java.io.File(temp.root, "skeletons/e1.skel").apply { parentFile!!.mkdirs() }
        file.writeBytes(buffer.array())
        val s = store()
        s.has("e1") shouldBe true
        val stored = s.load("e1")!!
        stored.marks shouldBe null
        stored.fps shouldBe 25.0
        stored.poses.single().frame shouldBe 7
    }

    @Test
    fun a_version_two_file_cut_inside_the_marks_is_refused() {
        val s = store()
        s.save("e1", listOf(pose(0)), 30.0, 1920, 1080, marks)
        val file = java.io.File(temp.root, "skeletons/e1.skel")
        file.writeBytes(file.readBytes().copyOf(100))
        s.load("e1") shouldBe null
    }

    @Test
    fun delete_on_a_never_saved_entry_does_not_throw() {
        store().delete("e1")
    }
}

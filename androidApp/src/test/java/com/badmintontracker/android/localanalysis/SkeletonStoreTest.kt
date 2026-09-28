package com.badmintontracker.android.localanalysis

import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.player.Coco
import com.badmintontracker.analysis.player.CourtSide
import com.badmintontracker.analysis.player.PlayerPose
import com.badmintontracker.analysis.player.PlayerSelection
import com.badmintontracker.analysis.player.PlayerTrack
import io.kotest.assertions.throwables.shouldThrow
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

    private fun pose(frame: Int, t: Double = frame / 25.0) = PlayerPose(
        frame = frame,
        timestamp = t,
        keypoints = List(Coco.COUNT) { Point(frame + it * 1.5, frame * 2.0 + it) },
        confidence = List(Coco.COUNT) { 0.5f + it / 100f },
    )

    /** An empty near track, for a saveAll call that only cares about poses. */
    private fun emptyTrack() = PlayerTrack(emptyList(), 0, emptyMap())

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
        stored.poses.single().camera shouldBe null
    }

    @Test
    fun a_version_two_file_cut_inside_the_marks_is_refused() {
        // Written by hand: save writes version 4 now, and this is about v2.
        val file = java.io.File(temp.root, "skeletons/e1.skel").apply { parentFile!!.mkdirs() }
        file.writeBytes(legacyFile(2, listOf(CourtSide.NEAR to listOf(pose(0))), marks).copyOf(100))
        store().load("e1") shouldBe null
    }

    @Test
    fun a_version_four_file_cut_inside_the_marks_is_refused() {
        val s = store()
        s.save("e1", listOf(pose(0)), 30.0, 1920, 1080, marks)
        val file = java.io.File(temp.root, "skeletons/e1.skel")
        file.writeBytes(file.readBytes().copyOf(100))
        s.load("e1") shouldBe null
    }

    @Test
    fun a_marks_flag_this_store_never_writes_is_refused() {
        val s = store()
        s.save("e1", listOf(pose(0)), 30.0, 1920, 1080, marks)
        val file = java.io.File(temp.root, "skeletons/e1.skel")
        val bytes = file.readBytes()
        val flag = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        // The flag sits after magic, version, fps, width and height: 4+4+8+4+4.
        flag.getInt(24) shouldBe 1
        flag.putInt(24, 7)
        file.writeBytes(bytes)
        s.load("e1") shouldBe null
    }

    @Test
    fun delete_on_a_never_saved_entry_does_not_throw() {
        store().delete("e1")
    }

    @Test
    fun `a v2 file written by the previous build still loads`() {
        // SkeletonStore already read v1 and v2 from the header alone, unlike
        // PlayerTrackStore. Pinned anyway: the two stores start from
        // different places and a change made to both at once is exactly when
        // that asymmetry gets flattened.
        // Written by hand since save moved on to version 4.
        val store = SkeletonStore(temp.root)
        java.io.File(temp.root, "skeletons/e1.skel").apply { parentFile!!.mkdirs() }
            .writeBytes(legacyFile(2, listOf(CourtSide.NEAR to listOf(pose(frame = 0, t = 0.0))), null))

        store.has("e1") shouldBe true
        val stored = store.load("e1")!!
        stored.tracks.size shouldBe 1
        stored.tracks[0].side shouldBe CourtSide.NEAR
        stored.poses.size shouldBe 1
        stored.poses.single().camera shouldBe null
    }

    @Test
    fun `two players round trip through v3`() {
        // Written by hand since saveAll moved on to version 4: a cloud
        // skeleton already on a phone is a v3 file, and it keeps loading.
        val store = SkeletonStore(temp.root)
        val near = listOf(pose(frame = 0, t = 0.0), pose(frame = 1, t = 0.033))
        val far = listOf(pose(frame = 0, t = 0.0))

        java.io.File(temp.root, "${TrackSource.CLOUD.skeletonDir}/e1.skel").apply { parentFile!!.mkdirs() }
            .writeBytes(legacyFile(3, listOf(CourtSide.NEAR to near, CourtSide.FAR to far), null, fps = 59.94))

        store.has("e1", TrackSource.CLOUD) shouldBe true
        val stored = store.load("e1", TrackSource.CLOUD)!!
        stored.fps shouldBe 59.94
        stored.videoWidth shouldBe 1920
        stored.tracks.map { it.side } shouldBe listOf(CourtSide.NEAR, CourtSide.FAR)
        stored.tracks[0].poses shouldBe near
        stored.tracks[1].poses shouldBe far
        // The near list, for every caller that predates the far player.
        stored.poses shouldBe near
        stored.tracks.flatMap { it.poses }.all { it.camera == null } shouldBe true
    }

    @Test
    fun `the court marks travel with a cloud file too`() {
        // A reading in metres needs the homography those marks fit, and the
        // local entry that holds them can be deleted while this file lives
        // on. That reasoning does not change because there are two players.
        val store = SkeletonStore(temp.root)

        store.saveAll(
            "e1", TrackSource.CLOUD,
            listOf(PlayerSelection(CourtSide.NEAR, emptyTrack(), listOf(pose(0, 0.0)))),
            fps = 30.0, videoWidth = 1920, videoHeight = 1080, marks = marks,
        )

        store.load("e1", TrackSource.CLOUD)!!.marks shouldBe marks
    }

    @Test
    fun `a deleted local skeleton leaves the cloud one alone`() {
        // The concrete consequence of the two subtrees. skeletonAction
        // returns DELETE for a run that asked for rallies only, and that run
        // must not be able to reach a cloud artifact.
        val store = SkeletonStore(temp.root)
        store.save("e1", listOf(pose(0, 0.0)), 30.0, 1920, 1080, null)
        store.saveAll(
            "e1", TrackSource.CLOUD,
            listOf(PlayerSelection(CourtSide.NEAR, emptyTrack(), listOf(pose(0, 0.0)))),
            fps = 30.0, videoWidth = 1920, videoHeight = 1080, marks = null,
        )

        store.delete("e1")

        store.has("e1", TrackSource.LOCAL) shouldBe false
        store.has("e1", TrackSource.CLOUD) shouldBe true
    }

    // Version 4: every pose keeps the camera it was taken with (design §5.3).

    private val panLeft = listOf(listOf(1.0, 0.0, 30.0), listOf(0.0, 1.0, -12.0), listOf(0.0, 0.0, 1.0))
    private val tiltedPan = listOf(
        listOf(1.01, 0.002, 30.5), listOf(-0.003, 0.99, -12.25), listOf(0.00001, -0.00002, 1.0),
    )

    private fun onFile(source: TrackSource = TrackSource.LOCAL) =
        java.io.File(temp.root, "${source.skeletonDir}/e1.skel")

    @Test
    fun a_device_save_writes_version_4_and_round_trips_cameras() {
        val s = store()
        val poses = listOf(pose(0).copy(camera = panLeft), pose(1).copy(camera = tiltedPan))
        s.save("e1", poses, 30.0, 1920, 1080, marks)
        val header = java.nio.ByteBuffer.wrap(onFile().readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.getInt(4) shouldBe 4
        // hasCamera sits after the marks: 4+4+8+4+4+4 + 12 x 16.
        header.getInt(220) shouldBe 1
        s.has("e1") shouldBe true
        val stored = s.load("e1")!!
        stored.tracks.map { it.side } shouldBe listOf(CourtSide.NEAR)
        // Bit for bit: the matrix is stored as the doubles it was, h22 as the
        // 1.0 it always is.
        stored.poses.map { it.camera } shouldBe listOf(panLeft, tiltedPan)
        stored.poses shouldBe poses
    }

    @Test
    fun poses_without_cameras_write_hasCamera_0_and_no_camera_bytes() {
        val s = store()
        s.save("e1", listOf(pose(0), pose(1), pose(2)), 30.0, 1920, 1080, null)
        val bytes = onFile().readBytes()
        // The v3 formula (header, one track header, three poses) plus the flag.
        val v3 = 4 + 4 + 8 + 4 + 4 + 4 + 12 * 16 + 4 + (4 + 4) + 3 * 216
        bytes.size shouldBe v3 + 4
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(220) shouldBe 0
        s.load("e1")!!.poses.map { it.camera } shouldBe listOf(null, null, null)
    }

    @Test
    fun a_cloud_saveAll_writes_version_4_with_both_tracks() {
        val store = store()
        val near = listOf(pose(0).copy(camera = panLeft), pose(1).copy(camera = tiltedPan))
        val far = listOf(pose(0).copy(camera = tiltedPan))
        store.saveAll(
            "e1", TrackSource.CLOUD,
            listOf(
                PlayerSelection(CourtSide.NEAR, emptyTrack(), near),
                PlayerSelection(CourtSide.FAR, emptyTrack(), far),
            ),
            fps = 59.94, videoWidth = 1920, videoHeight = 1080, marks = marks,
        )
        java.nio.ByteBuffer.wrap(onFile(TrackSource.CLOUD).readBytes())
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(4) shouldBe 4
        val stored = store.load("e1", TrackSource.CLOUD)!!
        stored.tracks.map { it.side } shouldBe listOf(CourtSide.NEAR, CourtSide.FAR)
        stored.tracks[0].poses shouldBe near
        stored.tracks[1].poses shouldBe far
        stored.marks shouldBe marks
    }

    @Test
    fun version_1_2_and_3_files_still_load_without_cameras() {
        val s = store()
        for (version in 1..3) {
            onFile().apply { parentFile!!.mkdirs() }
                .writeBytes(legacyFile(version, listOf(CourtSide.NEAR to listOf(pose(4), pose(5))), marks))
            s.has("e1") shouldBe true
            val stored = s.load("e1")!!
            stored.poses.map { it.frame } shouldBe listOf(4, 5)
            stored.poses.map { it.camera } shouldBe listOf(null, null)
            stored.marks shouldBe (if (version == 1) null else marks)
        }
    }

    @Test
    fun a_file_mixing_poses_with_and_without_cameras_is_refused_at_save() {
        // Selection never returns such a list (design §5.3), and the file has
        // one flag for all of them: writing it would drop cameras or invent them.
        val s = store()
        shouldThrow<IllegalArgumentException> {
            s.save("e1", listOf(pose(0).copy(camera = panLeft), pose(1)), 30.0, 1920, 1080, null)
        }
        shouldThrow<IllegalArgumentException> {
            s.saveAll(
                "e1", TrackSource.CLOUD,
                listOf(
                    PlayerSelection(CourtSide.NEAR, emptyTrack(), listOf(pose(0).copy(camera = panLeft))),
                    PlayerSelection(CourtSide.FAR, emptyTrack(), listOf(pose(0))),
                ),
                fps = 30.0, videoWidth = 1920, videoHeight = 1080, marks = null,
            )
        }
        s.has("e1") shouldBe false
        s.has("e1", TrackSource.CLOUD) shouldBe false
    }

    @Test
    fun a_camera_flag_this_store_never_writes_is_refused() {
        val s = store()
        s.save("e1", listOf(pose(0)), 30.0, 1920, 1080, null)
        val bytes = onFile().readBytes()
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(220, 2)
        onFile().writeBytes(bytes)
        s.load("e1") shouldBe null
    }

    @Test
    fun a_version_4_file_cut_inside_a_camera_is_refused() {
        val s = store()
        s.save("e1", listOf(pose(0).copy(camera = panLeft)), 30.0, 1920, 1080, null)
        val bytes = onFile().readBytes()
        onFile().writeBytes(bytes.copyOf(bytes.size - 8))
        s.load("e1") shouldBe null
    }

    @Test
    fun save_writes_what_saveAll_writes_for_a_lone_near_track() {
        // One writer behind both: a device file is a cloud file with one track.
        val s = store()
        val poses = listOf(pose(0).copy(camera = panLeft), pose(1).copy(camera = tiltedPan))
        s.save("e1", poses, 30.0, 1920, 1080, marks)
        s.saveAll(
            "e1", TrackSource.CLOUD, listOf(PlayerSelection(CourtSide.NEAR, emptyTrack(), poses)),
            30.0, 1920, 1080, marks,
        )
        onFile().readBytes().toList() shouldBe onFile(TrackSource.CLOUD).readBytes().toList()
    }

    /**
     * The shared fixture, byte for byte. iOS's AnalysisStoreTests holds the
     * same hex: both platforms load it, and both platforms' writers produce
     * it from [fixtureSelections], which is what makes a skeleton written on
     * one phone readable on the other.
     *
     * Two tracks, one pose each, marks, and a camera on every pose, in the
     * version 4 layout: "SKEL", 4, fps, width, height, hasMarks, 12 marks,
     * hasCamera, trackCount, then per track side, poseCount and poses, each
     * pose followed by h00 h01 h02 h10 h11 h12 h20 h21.
     */
    private val sharedV4Fixture = """
            534b454c04000000b81e85eb51f83d4080070000380400000100000000000000
            004c8440cdcccccccc4c7e403333333333f593403333333333137e409a999999
            99ad9840cdcccccccc348f4000000000008076400000000000608f4000000000
            003081403333333333bf8440cdcccccccc9895409a99999999cd8440cdcccccc
            cc8c7f4000000000003c87400000000000389640cdcccccccc10874000000000
            006082409a999999999982403333333333eb94409a99999999998240cdcccccc
            cc308e4033333333338b8240cdcccccccc308e40666666666602874001000000
            0200000000000000010000000c000000aa60545227a0d93f0000c84200004843
            0000003d0000dc4200004d430000803d0000f042000052430000c03d00000243
            000057430000003e00000c4300005c430000203e00001643000061430000403e
            00002043000066430000603e00002a4300006b430000803e0000344300007043
            0000903e00003e43000075430000a03e0000484300007a430000b03e00005243
            00007f430000c03e00005c43000082430000d03e00006643008084430000e03e
            00007043000087430000f03e00007a43008089430000003f0000824300008c43
            0000083f295c8fc2f528f03ffca9f1d24d62603f0000000000803e40fa7e6abc
            749368bfae47e17a14aeef3f00000000008028c0f168e388b5f8e43ef168e388
            b5f8f4be01000000010000000d000000e09c11a5bdc1db3f00002f4400009643
            0000803f00003044000097430000783f00003144000098430000703f00003244
            000099430000683f0000334400009a430000603f0000344400009b430000583f
            0000354400009c430000503f0000364400009d430000483f0000374400009e43
            0000403f0000384400009f430000383f000039440000a0430000303f00003a44
            0000a1430000283f00003b440000a2430000203f00003c440000a3430000183f
            00003d440000a4430000103f00003e440000a5430000083f00003f440000a643
            0000003f5c8fc2f5285cef3ffca9f1d24d6250bf00000000008021c0fca9f1d2
            4d62703f52b81e85eb51f03f0000000000001a40691d554d1075ffbef168e388
            b5f8e43e
    """.filterNot { it.isWhitespace() }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun fixtureSelections(): List<PlayerSelection> {
        val near = PlayerPose(
            frame = 12, timestamp = 0.4004,
            keypoints = List(Coco.COUNT) { Point(100.0 + 10 * it, 200.0 + 5 * it) },
            confidence = List(Coco.COUNT) { (it + 1) / 32f },
            camera = tiltedPan,
        )
        val far = PlayerPose(
            frame = 13, timestamp = 0.4337,
            keypoints = List(Coco.COUNT) { Point(700.0 + 4 * it, 300.0 + 2 * it) },
            confidence = List(Coco.COUNT) { (32 - it) / 32f },
            camera = listOf(listOf(0.98, -0.001, -8.75), listOf(0.004, 1.02, 6.5), listOf(-0.00003, 0.00001, 1.0)),
        )
        return listOf(
            PlayerSelection(CourtSide.NEAR, emptyTrack(), listOf(near)),
            PlayerSelection(CourtSide.FAR, emptyTrack(), listOf(far)),
        )
    }

    @Test
    fun the_shared_v4_fixture_loads_and_is_what_saveAll_writes() {
        val store = store()
        onFile(TrackSource.CLOUD).apply { parentFile!!.mkdirs() }.writeBytes(sharedV4Fixture)
        val stored = store.load("e1", TrackSource.CLOUD)!!
        val expected = fixtureSelections()
        stored.fps shouldBe 29.97
        stored.videoWidth shouldBe 1920
        stored.videoHeight shouldBe 1080
        stored.marks shouldBe marks
        stored.tracks.map { it.side } shouldBe listOf(CourtSide.NEAR, CourtSide.FAR)
        stored.tracks[0].poses shouldBe expected[0].poses
        stored.tracks[1].poses shouldBe expected[1].poses

        store.saveAll("e2", TrackSource.CLOUD, expected, 29.97, 1920, 1080, marks)
        java.io.File(temp.root, "${TrackSource.CLOUD.skeletonDir}/e2.skel").readBytes().toList() shouldBe
            sharedV4Fixture.toList()
    }

    /**
     * A file in an older layout, written by hand so a writer that stopped
     * emitting it cannot make a compatibility test pass. v1 and v2 hold one
     * near track; v1 has no marks.
     */
    private fun legacyFile(
        version: Int,
        tracks: List<Pair<CourtSide, List<PlayerPose>>>,
        marks: com.badmintontracker.analysis.geometry.CourtKeypoints?,
        fps: Double = 30.0,
    ): ByteArray {
        val poseCount = tracks.sumOf { it.second.size }
        val buffer = java.nio.ByteBuffer.allocate(28 + 192 + 4 + tracks.size * 8 + poseCount * 216)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.put("SKEL".toByteArray(Charsets.US_ASCII)).putInt(version).putDouble(fps).putInt(1920).putInt(1080)
        if (version >= 2) {
            buffer.putInt(if (marks != null) 1 else 0)
            val pixels = marks?.let {
                listOf(
                    it.topLeft, it.topRight, it.bottomRight, it.bottomLeft, it.netLeft, it.netRight,
                    it.serviceLineNearLeft, it.serviceLineNearRight, it.serviceLineFarLeft,
                    it.serviceLineFarRight, it.centerNear, it.centerFar,
                )
            }
            repeat(12) { buffer.putDouble(pixels?.get(it)?.x ?: 0.0).putDouble(pixels?.get(it)?.y ?: 0.0) }
        }
        if (version == 3) buffer.putInt(tracks.size) else require(tracks.single().first == CourtSide.NEAR)
        for ((side, poses) in tracks) {
            if (version == 3) buffer.putInt(side.ordinal)
            buffer.putInt(poses.size)
            for (p in poses) {
                buffer.putInt(p.frame).putDouble(p.timestamp)
                for (k in 0 until Coco.COUNT) {
                    buffer.putFloat(p.keypoints[k].x.toFloat()).putFloat(p.keypoints[k].y.toFloat())
                        .putFloat(p.confidence[k])
                }
            }
        }
        return buffer.array().copyOf(buffer.position())
    }
}

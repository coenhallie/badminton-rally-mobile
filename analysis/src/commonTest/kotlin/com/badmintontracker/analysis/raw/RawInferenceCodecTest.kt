package com.badmintontracker.analysis.raw

import com.badmintontracker.analysis.camera.CameraMotion
import com.badmintontracker.analysis.camera.CameraSample
import com.badmintontracker.analysis.corpus.readResourceBytesOrNull
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class RawInferenceCodecTest {

    // encode() always writes RawInferenceCodec.VERSION regardless of this field
    // (only decode's output ever reflects the wire), so this must track VERSION
    // for decode(encode(sample)) shouldBe sample to hold.
    private val header = RawHeader(
        version = RawInferenceCodec.VERSION,
        fps = 59.94,
        totalFrames = 1234,
        videoWidth = 1920,
        videoHeight = 1080,
        modelVersion = "tracknet@abc123",
    )

    /** Distinct, non-round values everywhere so a field-order swap cannot pass. */
    private val sample = RawInference(
        header = header,
        frames = listOf(
            RawFrame(
                frame = 7,
                timestamp = 1.0 / 3.0,
                shuttle = RawShuttle(x = 123.45f, y = 678.9f, confidence = 0.875f, visible = true),
                boxes = listOf(
                    RawBox(classId = 2, confidence = 0.5f, x1 = 1.5f, y1 = 2.5f, x2 = 3.5f, y2 = 4.5f),
                    RawBox(classId = 3, confidence = 0.25f, x1 = 5.5f, y1 = 6.5f, x2 = 7.5f, y2 = 8.5f),
                ),
                persons = emptyList(),
            ),
            RawFrame(frame = 8, timestamp = 2.0 / 3.0, shuttle = null, boxes = emptyList(), persons = emptyList()),
        ),
    )

    @Test
    fun a_record_stream_round_trips() {
        RawInferenceCodec.decode(RawInferenceCodec.encode(sample)) shouldBe sample
    }

    @Test
    fun a_person_record_round_trips() {
        // Stage 1 writes no persons, but the format carries them so Stage 3
        // does not need a version bump. Unread fields rot, so this exercises
        // the path now.
        val withPerson = sample.copy(
            frames = listOf(
                sample.frames[0].copy(
                    persons = listOf(
                        RawPerson(
                            box = RawBox(1, 0.9f, 10f, 20f, 30f, 40f),
                            keypoints = (0 until 17).map { RawKeypoint(it * 1.5f, it * 2.5f, it * 0.01f) },
                        )
                    )
                )
            )
        )
        RawInferenceCodec.decode(RawInferenceCodec.encode(withPerson)) shouldBe withPerson
    }

    @Test
    fun the_encoding_is_little_endian() {
        // The Swift writer is the other half of this format. A self-consistent
        // Kotlin round trip proves nothing about byte order; this does.
        val bytes = RawInferenceCodec.encode(
            sample.copy(header = header.copy(totalFrames = 1), frames = emptyList())
        )
        val offset = RawInferenceCodec.TOTAL_FRAMES_OFFSET
        listOf(bytes[offset], bytes[offset + 1], bytes[offset + 2], bytes[offset + 3]) shouldBe
            listOf(1.toByte(), 0.toByte(), 0.toByte(), 0.toByte())
    }

    @Test
    fun a_foreign_file_is_rejected_by_its_magic() {
        val bytes = RawInferenceCodec.encode(sample)
        bytes[0] = 'X'.code.toByte()
        val e = assertFailsWith<IllegalArgumentException> { RawInferenceCodec.decode(bytes) }
        (e.message?.contains("RawInference") == true) shouldBe true
    }

    @Test
    fun an_unknown_version_is_rejected_by_name() {
        // A future writer must fail loudly here rather than be misread as a
        // version this build understands. Version 2 is now readable, so this
        // pokes 3 instead.
        val bytes = RawInferenceCodec.encode(sample)
        bytes[RawInferenceCodec.VERSION_OFFSET] = 3
        val e = assertFailsWith<IllegalArgumentException> { RawInferenceCodec.decode(bytes) }
        (e.message?.contains("version") == true) shouldBe true
    }

    @Test fun a_camera_round_trips() {
        val m = listOf(listOf(1.001, 0.002, 14.5), listOf(-0.003, 0.999, -8.25), listOf(1e-6, -2e-6, 1.0))
        val raw = sample.copy(camera = CameraMotion.of(0.1, listOf(
            CameraSample(0.1, listOf(listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0))),
            CameraSample(0.2, m),
        )))
        RawInferenceCodec.decode(RawInferenceCodec.encode(raw)) shouldBe raw
    }

    @Test fun no_camera_round_trips_as_no_camera() {
        val raw = sample
        RawInferenceCodec.decode(RawInferenceCodec.encode(raw)).camera shouldBe null
    }

    @Test fun encode_writes_version_2() {
        val bytes = RawInferenceCodec.encode(sample)
        (bytes[RawInferenceCodec.VERSION_OFFSET].toInt() and 0xFF) shouldBe 2
    }

    @Test fun a_version_1_stream_still_reads_with_no_camera() {
        // Encode, then rewrite it as the version 1 a deployed worker produced:
        // version byte 1 and no camera section (the trailing hasCamera byte removed).
        val v2 = RawInferenceCodec.encode(sample)
        val v1 = v2.copyOf(v2.size - 1).also { it[RawInferenceCodec.VERSION_OFFSET] = 1 }
        RawInferenceCodec.decode(v1).camera shouldBe null
    }

    @Test fun bytes_after_the_camera_section_are_rejected() {
        val bytes = RawInferenceCodec.encode(sample) + byteArrayOf(0)
        shouldThrow<IllegalArgumentException> { RawInferenceCodec.decode(bytes) }
    }

    @Test fun a_truncated_camera_section_is_rejected() {
        val m = listOf(listOf(1.0, 0.0, 3.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0))
        val bytes = RawInferenceCodec.encode(sample.copy(camera = CameraMotion.of(0.1, listOf(CameraSample(0.1, m)))))
        shouldThrow<IllegalArgumentException> { RawInferenceCodec.decode(bytes.copyOf(bytes.size - 4)) }
    }

    @Test
    fun a_truncated_stream_fails_rather_than_returning_short() {
        // A half-written file from an interrupted analysis must not decode to
        // a plausible shorter video, which would silently move every rally.
        val bytes = RawInferenceCodec.encode(sample)
        assertFailsWith<IllegalArgumentException> {
            RawInferenceCodec.decode(bytes.copyOf(bytes.size - 5))
        }
    }

    /** Where the frame count sits: after the fixed header and the model version string. */
    private fun frameCountOffset(): Int =
        4 + 4 + 8 + 4 + 4 + 4 + 4 + sample.header.modelVersion.encodeToByteArray().size

    private fun ByteArray.withIntAt(offset: Int, value: Int): ByteArray = copyOf().also {
        for (i in 0 until 4) it[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }

    @Test
    fun a_corrupt_count_is_rejected_before_anything_is_allocated_for_it() {
        // These bytes come off the network: the cloud pose artifact is tens of
        // megabytes, downloaded on a phone. A flipped count used to reach
        // ArrayList(n) and List(n) unchecked, so the truncation guard only got
        // its say after an allocation of two billion slots had already taken
        // the app down with OutOfMemoryError.
        val bytes = RawInferenceCodec.encode(sample)
        assertFailsWith<IllegalArgumentException> {
            RawInferenceCodec.decode(bytes.withIntAt(frameCountOffset(), Int.MAX_VALUE))
        }
    }

    @Test
    fun a_negative_count_is_rejected_by_name() {
        val bytes = RawInferenceCodec.encode(sample)
        val failure = assertFailsWith<IllegalArgumentException> {
            RawInferenceCodec.decode(bytes.withIntAt(frameCountOffset(), -1))
        }
        failure.message!!.contains("RawInference") shouldBe true
    }

    @Test
    fun a_length_that_overflows_the_position_is_still_a_truncation() {
        // pos + n wraps negative for a large enough n, and a wrapped sum passed
        // the bounds check. The model version's length is the first one read.
        val bytes = RawInferenceCodec.encode(sample)
        val failure = assertFailsWith<IllegalArgumentException> {
            RawInferenceCodec.decode(bytes.withIntAt(4 + 4 + 8 + 4 + 4 + 4, Int.MAX_VALUE))
        }
        failure.message!!.contains("truncated") shouldBe true
    }

    @Test
    fun an_absent_shuttle_is_distinct_from_an_invisible_one() {
        // Two different facts: the model produced no output for this frame,
        // versus it produced one that resolved to no blob.
        val invisible = sample.frames[0].copy(
            shuttle = RawShuttle(0f, 0f, 0f, visible = false)
        )
        val absent = sample.frames[0].copy(shuttle = null)
        val decoded = RawInferenceCodec.decode(
            RawInferenceCodec.encode(sample.copy(frames = listOf(invisible, absent)))
        )
        decoded.frames[0].shuttle shouldBe RawShuttle(0f, 0f, 0f, visible = false)
        decoded.frames[1].shuttle shouldBe null
    }

    @Test
    fun a_frame_with_no_persons_costs_one_int32() {
        // The assertion that keeps Stage 3 from forcing a format version bump.
        val bare = RawFrame(1, 0.0, null, emptyList(), emptyList())
        val one = RawInferenceCodec.encode(sample.copy(frames = listOf(bare)))
        val two = RawInferenceCodec.encode(sample.copy(frames = listOf(bare, bare)))
        // frame(4) + timestamp(8) + shuttleFlag(1) + boxCount(4) + personCount(4)
        (two.size - one.size) shouldBe 21
    }

    /**
     * The stream Python writes decodes to the values Python was given.
     *
     * This is the ONLY check that the cloud's writer and the phone's reader
     * agree. `backend/raw_inference_codec.py` has no decoder on purpose, so
     * nothing on that side can confirm its own output; the fixture is
     * regenerated by
     *   python backend/scripts/verify_raw_inference_codec.py --fixture <path>
     * and the sample values below are that script's, copied by hand. If the
     * two ever disagree, the format has drifted and a cloud analysis would
     * produce a skeleton drawn from misread floats rather than an error.
     *
     * Null on native, where readResourceBytesOrNull has no resources to read.
     * Skipped rather than failed for the reason the corpus fixtures are:
     * a guard that cannot run on a target must not stop that target's suite.
     *
     * `cloud-poses.rawi` is frozen at version 1: `raw_inference_codec.py` can
     * no longer write version 1 (its `VERSION` constant is 2), so this fixture
     * will never be regenerated. It stays only to prove the reader keeps
     * reading version 1 files a deployed worker already produced.
     * [the_python_v2_writer_and_this_reader_agree] is the version 2 version of
     * this check.
     */
    @Test
    fun the_python_writer_and_this_reader_agree() {
        val bytes = readResourceBytesOrNull("/raw/cloud-poses.rawi") ?: return

        val decoded = RawInferenceCodec.decode(bytes)

        decoded.header.version shouldBe 1
        decoded.camera.shouldBeNull()
        decoded.header.fps shouldBe 59.94
        decoded.header.totalFrames shouldBe 1234
        decoded.header.videoWidth shouldBe 1920
        decoded.header.videoHeight shouldBe 1080
        decoded.header.modelVersion shouldBe "cloud:yolo11x-pose:abc123"

        decoded.frames.size shouldBe 2

        val first = decoded.frames[0]
        first.frame shouldBe 7
        first.timestamp shouldBe (1.0 / 3.0)
        first.shuttle shouldBe null
        first.boxes shouldBe emptyList()
        first.persons.size shouldBe 2

        // Both players, in the order the worker wrote them. Order is not
        // meaningful to :analysis - the side gates decide who is who - but a
        // reader that silently reordered would hide a writer that did.
        val near = first.persons[0]
        near.box shouldBe RawBox(classId = 1, confidence = 0.9f, x1 = 10f, y1 = 20f, x2 = 30f, y2 = 40f)
        near.keypoints.size shouldBe 17
        near.keypoints[0] shouldBe RawKeypoint(0f, 0f, 0f)
        near.keypoints[16] shouldBe RawKeypoint(16 * 1.5f, 16 * 2.5f, 16 * 0.01f)

        val far = first.persons[1]
        far.box.confidence shouldBe 0.7f
        far.keypoints[16] shouldBe RawKeypoint(16 * 1.25f, 16 * 2.25f, 16 * 0.02f)

        val second = decoded.frames[1]
        second.frame shouldBe 8
        second.timestamp shouldBe (2.0 / 3.0)
        second.persons shouldBe emptyList()
    }

    /**
     * The version 2 counterpart of [the_python_writer_and_this_reader_agree]:
     * same header and frames (verified byte-identical to `cloud-poses.rawi`
     * except the version field), plus a two-sample camera section. Generated
     * by `python backend/scripts/verify_raw_inference_codec.py --fixture <path>`
     * in `badminton-tracker`; the values below are that script's fixture
     * definition, copied by hand.
     */
    @Test
    fun the_python_v2_writer_and_this_reader_agree() {
        val bytes = readResourceBytesOrNull("/raw/cloud-poses-v2.rawi") ?: return

        val decoded = RawInferenceCodec.decode(bytes)

        decoded.header.version shouldBe 2
        decoded.header.fps shouldBe 59.94
        decoded.header.totalFrames shouldBe 1234
        decoded.header.videoWidth shouldBe 1920
        decoded.header.videoHeight shouldBe 1080
        decoded.header.modelVersion shouldBe "cloud:yolo11x-pose:abc123"

        decoded.frames.size shouldBe 2

        val first = decoded.frames[0]
        first.frame shouldBe 7
        first.timestamp shouldBe (1.0 / 3.0)
        first.shuttle shouldBe null
        first.boxes shouldBe emptyList()
        first.persons.size shouldBe 2

        val near = first.persons[0]
        near.box shouldBe RawBox(classId = 1, confidence = 0.9f, x1 = 10f, y1 = 20f, x2 = 30f, y2 = 40f)
        near.keypoints.size shouldBe 17
        near.keypoints[0] shouldBe RawKeypoint(0f, 0f, 0f)
        near.keypoints[16] shouldBe RawKeypoint((16 * 1.5).toFloat(), (16 * 2.5).toFloat(), (16 * 0.01).toFloat())

        val far = first.persons[1]
        far.box.confidence shouldBe 0.7f
        far.keypoints[16] shouldBe RawKeypoint((16 * 1.25).toFloat(), (16 * 2.25).toFloat(), (16 * 0.02).toFloat())

        val second = decoded.frames[1]
        second.frame shouldBe 8
        second.timestamp shouldBe (2.0 / 3.0)
        second.persons shouldBe emptyList()

        val camera = decoded.camera.shouldNotBeNull()
        camera.referenceTimestamp shouldBe 0.5
        camera.samples.size shouldBe 2

        val sample0 = camera.samples[0]
        sample0.timestamp shouldBe 0.1
        sample0.referenceToFrame shouldBe listOf(
            listOf(1.1, 0.05, 12.0),
            listOf(0.02, 0.95, -8.0),
            listOf(0.0003, -0.0004, 1.0),
        )

        val sample1 = camera.samples[1]
        sample1.timestamp shouldBe 0.6
        sample1.referenceToFrame shouldBe listOf(
            listOf(0.98, -0.03, -5.5),
            listOf(0.04, 1.02, 6.25),
            listOf(-0.0002, 0.0005, 1.0),
        )
    }
}

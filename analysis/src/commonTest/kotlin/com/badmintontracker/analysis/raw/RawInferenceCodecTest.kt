package com.badmintontracker.analysis.raw

import com.badmintontracker.analysis.corpus.readResourceBytesOrNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class RawInferenceCodecTest {

    private val header = RawHeader(
        version = 1,
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
        // A future writer must fail loudly here rather than be misread as v1.
        val bytes = RawInferenceCodec.encode(sample)
        bytes[RawInferenceCodec.VERSION_OFFSET] = 2
        val e = assertFailsWith<IllegalArgumentException> { RawInferenceCodec.decode(bytes) }
        (e.message?.contains("version") == true) shouldBe true
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
     */
    @Test
    fun the_python_writer_and_this_reader_agree() {
        val bytes = readResourceBytesOrNull("/raw/cloud-poses.rawi") ?: return

        val decoded = RawInferenceCodec.decode(bytes)

        decoded.header.version shouldBe 1
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
}

package com.badmintontracker.analysis.camera

import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.player.CourtSide
import com.badmintontracker.analysis.player.selectPlayers
import com.badmintontracker.analysis.raw.RawInferenceCodec
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.test.Test

/**
 * The cloud's Phase 2 loop, run on this machine on the handheld recording,
 * read and selected the way a phone installs a cloud pose file (design
 * `docs/plans/2026-09-27-camera-motion-design.md` §8.6).
 *
 * The pose file comes from badminton-tracker's
 * `backend/scripts/run_phase2_locally.py`, which runs the worker's own loop
 * (medium pose model, camera followed in-loop) and builds the artifact the way
 * the worker does. Here it goes through [RawInferenceCodec.decode] and
 * [selectPlayers] with the ideal marks, and the NEAR track is held against
 * `reference_tracks.json`'s `ref_track`: the same selection rules with the
 * camera followed by every-frame ORB (§1). The bounds are §8.6's, looser than
 * the device run's because the model differs and the cloud's clock runs one
 * frame late.
 *
 * Joined by frame, not by timestamp. Both passes decode the video in order
 * from the start, so cloud frame `k` (numbered from 1) is reference index
 * `k - 1`. The cloud's timestamps are read before each frame is decoded, so
 * its first two frames both say 0.0 s and every later one names its
 * predecessor's time (§3); a timestamp join would pair each frame with its
 * neighbour.
 *
 * Coverage is [com.badmintontracker.analysis.player.PlayerTrack.coverage]:
 * positions over the frames that carry anyone at all, the "pose frames" §8.6's
 * gate is written in. The share of every frame in the file is printed beside
 * it.
 *
 * The three bounds are checked together, so an accuracy miss is reported
 * whatever the coverage does. Coverage depends on the cloud's court-area
 * filter admitting her by her ankles while the camera is followed
 * (badminton-tracker `court_area_admits`): with the box bottom alone it
 * dropped her whenever she stood at the near baseline, and this read 0.916.
 * With it, 2026-09-27: 0.974, median 6.8 cm, p95 22.4 cm.
 *
 * BLOCKED on a corpus: skips loudly unless the harness has written
 * `~/shuttl-corpus/IMG_5892/cloud-local/poses.raw` next to the corpus's
 * `marks_ideal.json` and `reference_tracks.json`. `ANALYSIS_REQUIRE_CORPUS=1`
 * turns the skip into a failure, the convention of `CorpusFixture.jvmShared.kt`'s
 * `corpusIsRequired()`. Read straight off the filesystem, like
 * [CameraMotionCorpusTest], because a pose file of a whole video has no place
 * on the test classpath.
 */
class CloudLocalRunCorpusTest {

    private val corpusDir = File(System.getProperty("user.home"), "shuttl-corpus/IMG_5892")

    @Test
    fun a_local_cloud_run_puts_the_near_player_where_the_reference_has_her() {
        val posesFile = File(corpusDir, "cloud-local/poses.raw")
        val marksFile = File(corpusDir, "marks_ideal.json")
        val referenceFile = File(corpusDir, "reference_tracks.json")
        val missing = listOf(posesFile, marksFile, referenceFile).filterNot { it.exists() }
        if (missing.isNotEmpty()) {
            val message = "SKIPPED: corpus file(s) ${missing.joinToString()} not present, so a local " +
                "cloud run was not selected against the reference. Run badminton-tracker's " +
                "backend/scripts/run_phase2_locally.py, which needs ~/shuttl-corpus/IMG_5892/ " +
                "(source.mov, marks_ideal.json) to already exist."
            if (System.getenv("ANALYSIS_REQUIRE_CORPUS") == "1") error(message)
            println(message)
            return
        }

        val raw = RawInferenceCodec.decode(posesFile.readBytes())
        val camera = raw.camera.shouldNotBeNull()
        val marks = parseMarks(marksFile)
        val reference = parseReference(referenceFile)

        val near = selectPlayers(raw, marks).first { it.side == CourtSide.NEAR }
        val track = near.track

        val errors = ArrayList<Double>()
        var withoutReference = 0
        for (sample in track.samples) {
            val expected = reference[sample.frame - 1]
            if (expected == null) {
                withoutReference++
                continue
            }
            errors.add(hypot(sample.courtPosition.x - expected.x, sample.courtPosition.y - expected.y))
        }

        println(
            "pose file: %d frames, %d camera samples; NEAR: %d positions over %d pose frames (coverage %.4f), %.4f of every frame"
                .format(
                    raw.frames.size, camera.samples.size, track.samples.size, track.framesWithPose,
                    track.coverage, track.samples.size.toDouble() / raw.frames.size,
                )
        )
        println("NEAR rejections: ${track.rejections}")
        errors.isEmpty() shouldBe false
        val median = percentile(errors, 50.0)
        val p95 = percentile(errors, 95.0)
        println(
            "against ref_track: median %.1f cm, p95 %.1f cm, worst %.1f cm, over %d frames (%d positions had no reference)"
                .format(median * 100, p95 * 100, errors.max() * 100, errors.size, withoutReference)
        )

        // All three together: a coverage miss must not hide an accuracy one.
        assertSoftly {
            median shouldBeLessThanOrEqual 0.08
            p95 shouldBeLessThanOrEqual 0.25
            track.coverage shouldBeGreaterThanOrEqual 0.95
        }
    }

    private fun readJson(file: File) = Json.parseToJsonElement(file.readText())

    private fun parseMarks(file: File): CourtKeypoints {
        val points = readJson(file).jsonObject.getValue("manual_court_keypoints").jsonObject
            .mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.double } }
        return CourtKeypoints.fromMap(points) ?: error("$file does not hold all 12 marks")
    }

    /** `ref_track`: 0-based frame index to the near player's court position in metres. */
    private fun parseReference(file: File): Map<Int, Point> =
        readJson(file).jsonObject.getValue("ref_track").jsonObject.entries.associate { (key, value) ->
            val xy = value.jsonArray
            key.toInt() to Point(xy[0].jsonPrimitive.double, xy[1].jsonPrimitive.double)
        }

    /** Linear-interpolated percentile, numpy's default (`np.percentile`). */
    private fun percentile(values: List<Double>, p: Double): Double {
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted[0]
        val rank = p / 100.0 * (sorted.size - 1)
        val lower = floor(rank).toInt()
        val upper = ceil(rank).toInt()
        if (lower == upper) return sorted[lower]
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (rank - lower)
    }
}

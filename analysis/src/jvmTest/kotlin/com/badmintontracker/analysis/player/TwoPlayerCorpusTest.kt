package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.compare.readCorpusMarks
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.geometry.invert3x3
import com.badmintontracker.analysis.raw.RawInference
import com.badmintontracker.analysis.raw.RawInferenceCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test

/**
 * Both players of a local cloud run, scored against the audited references
 * (design `docs/plans/2026-09-29-two-player-tracking-design.md` §3.3).
 *
 * Each pose file comes from badminton-tracker's
 * `backend/scripts/run_phase2_locally.py --video-id <id>`, the worker's own
 * loop run on this machine, and goes through [RawInferenceCodec.decode] and
 * [selectPlayers] exactly as a phone installs it. The references come from
 * `tools/models/players_reference.py`, audited by hand (§3.2).
 *
 * `TWO_PLAYER_POSES=baseline.raw` scores another file in each video's
 * `cloud-local/`, so today's worker and a changed one are read by one test.
 * Where `device-style/poses.raw` exists (`tools/models/device_style_poses.py`)
 * a `NEAR-dev` row scores the device path's Near side too.
 * `TWO_PLAYER_MAX_FRAME=<n>` scores only each file's first n frames, so a
 * `--max-frames` run compares with a full one.
 *
 * BLOCKED on a corpus: a video whose files are missing is skipped loudly;
 * `ANALYSIS_REQUIRE_CORPUS=1` turns a skip into a failure.
 */
class TwoPlayerCorpusTest {

    private val root = File(System.getProperty("user.home"), "shuttl-corpus")
    private val posesName = System.getenv("TWO_PLAYER_POSES") ?: "poses.raw"
    private val maxFrame = System.getenv("TWO_PLAYER_MAX_FRAME")?.toInt()

    /**
     * The gates (design §7) hold the current worker's files, poses.raw, to
     * account; another file is scored and printed only, unless
     * TWO_PLAYER_GATES=1 asks for the gates anyway (which is how they were
     * seen to fail on the baseline).
     */
    private val gated = maxFrame == null &&
        (System.getenv("TWO_PLAYER_GATES") == "1" || posesName == "poses.raw")
    private val misses = ArrayList<String>()

    /** The file, cut to its first [maxFrame] frames when set, so a partial run and a full one compare. */
    private fun read(file: File): RawInference {
        val raw = RawInferenceCodec.decode(file.readBytes())
        return maxFrame?.let { n -> raw.copy(frames = raw.frames.filter { it.frame <= n }) } ?: raw
    }

    @Test
    fun both_players_of_each_corpus_video() {
        println("pose file: cloud-local/$posesName" + (maxFrame?.let { ", frames 1..$it" } ?: ""))
        println(HEADER)
        for (id in CORPUS_IDS) {
            val dir = File(root, id)
            val poses = File(dir, "cloud-local/$posesName")
            val reference = references(dir)
            if (!poses.isFile || reference == null) {
                skip("$id: needs ${poses.path} and ${File(dir, "players_reference.json").path}")
                continue
            }
            val raw = read(poses)
            val selections = selectPlayers(raw, readCorpusMarks(dir))
            val lost = cameraLostFrames(raw)
            val lostWithPose = raw.frames.count { it.frame in lost && it.persons.isNotEmpty() }
            for (selection in selections) {
                val score = scoreSide(
                    selection.track.samples, selection.track.framesWithPose,
                    reference.getValue(selection.side), lost, lostWithPose,
                )
                println(row(id, selection.side.name, score, lost.size))
                if (gated) gate(id, selection.side, score)
            }
            // The device path hands selectPlayers every person its nano graph
            // finds; device runs keep Near only, so only Near is read (§5).
            val device = File(dir, "device-style/poses.raw")
            if (device.isFile) {
                val deviceRaw = read(device)
                val near = selectPlayers(deviceRaw, readCorpusMarks(dir)).first { it.side == CourtSide.NEAR }
                val deviceLost = cameraLostFrames(deviceRaw)
                val deviceLostWithPose = deviceRaw.frames.count { it.frame in deviceLost && it.persons.isNotEmpty() }
                val score = scoreSide(
                    near.track.samples, near.track.framesWithPose,
                    reference.getValue(CourtSide.NEAR), deviceLost, deviceLostWithPose,
                )
                println(row(id, "NEAR-dev", score, deviceLost.size))
            }
        }
        if (misses.isNotEmpty()) error("two-player gates missed:\n" + misses.joinToString("\n"))
    }

    /** Design §7's gates, against the baseline (the worker before the zoom pass, df8dede). */
    private fun gate(id: String, side: CourtSide, s: SideScore) {
        fun need(ok: Boolean, what: String) {
            if (!ok) misses.add("$id $side: $what")
        }
        val base = BASELINE[id to side] ?: return
        need(s.wrong <= base.wrong, "wrong person ${s.wrong} > baseline ${base.wrong}")
        when {
            id == ASASA && side == CourtSide.FAR -> {
                need(s.coverage >= 0.70, "coverage ${s.coverage} < 0.70")
                need((s.medianM ?: 1.0) <= 0.15, "median ${s.medianM} > 0.15 m")
            }
            side == CourtSide.NEAR -> {
                need(s.coverage >= base.coverage - 0.01, "coverage ${s.coverage} more than 1 point below ${base.coverage}")
                need(s.right >= base.right * 0.99, "right person ${s.right} more than 1% below ${base.right}")
            }
            else -> need(s.coverage > base.coverage, "coverage ${s.coverage} not above baseline ${base.coverage}")
        }
    }

    /** Pose-file frames whose camera the phone could not read: [RejectionReason.CAMERA_LOST]'s frames. */
    private fun cameraLostFrames(raw: RawInference): Set<Int> {
        val camera = raw.camera ?: return emptySet()
        return raw.frames.filter { f -> camera.at(f.timestamp)?.let { invert3x3(it) } == null }.map { it.frame }.toSet()
    }

    private fun references(dir: File): Map<CourtSide, SideReference>? {
        val file = File(dir, "players_reference.json")
        if (!file.isFile) return null
        val sides = Json.parseToJsonElement(file.readText()).jsonObject.getValue("sides").jsonObject
        return CourtSide.entries.associateWith { side -> sideReference(sides.getValue(side.name).jsonObject) }
    }

    private fun sideReference(side: JsonObject): SideReference = SideReference(
        empty = side["empty"]?.jsonPrimitive?.boolean ?: false,
        positions = side.getValue("positions").jsonObject.entries.associate { (k, v) ->
            val xy = v.jsonArray
            k.toInt() to Point(xy[0].jsonPrimitive.double, xy[1].jsonPrimitive.double)
        },
    )

    private fun skip(message: String) {
        val text = "SKIPPED: $message"
        if (System.getenv("ANALYSIS_REQUIRE_CORPUS") == "1") error(text)
        println(text)
    }

    private fun row(id: String, side: String, s: SideScore, lost: Int): String =
        "%-9s %-8s %7d %10.7f %6d %6d %7d %6d %8s %8s %6d".format(
            id.take(8), side, s.samples, s.coverage, s.right, s.wrong, s.unknown, s.falsePositives,
            s.medianM?.let { "%.1f".format(it * 100) } ?: "-", s.p95M?.let { "%.1f".format(it * 100) } ?: "-", lost,
        )

    private data class Baseline(val coverage: Double, val right: Int, val wrong: Int)

    companion object {
        const val ASASA = "d71b0798-c4ec-4038-bd3f-6967429c36f8"

        /**
         * The worker before the zoom pass (df8dede), read by today's phone (design §10).
         * Coverages to seven places, rounded up, so the baseline file never passes
         * its own "above baseline" gate by rounding.
         */
        private val BASELINE = mapOf(
            (ASASA to CourtSide.NEAR) to Baseline(1.000, 601, 0),
            (ASASA to CourtSide.FAR) to Baseline(0.000, 0, 0),
            ("743d7fb1-ad6e-44d2-bd35-f9a6c0892a87" to CourtSide.NEAR) to Baseline(0.9607603, 3067, 0),
            ("743d7fb1-ad6e-44d2-bd35-f9a6c0892a87" to CourtSide.FAR) to Baseline(0.8034949, 2488, 0),
            ("IMG_5892" to CourtSide.NEAR) to Baseline(0.9734068, 2046, 0),
            ("IMG_5892" to CourtSide.FAR) to Baseline(0.8505273, 1597, 0),
        )

        val CORPUS_IDS = listOf(
            ASASA,
            "743d7fb1-ad6e-44d2-bd35-f9a6c0892a87",
            "IMG_5892",
        )
        const val HEADER = "video     side     samples      cover  right  wrong unknown falsep median_cm  p95_cm  lost"
    }
}

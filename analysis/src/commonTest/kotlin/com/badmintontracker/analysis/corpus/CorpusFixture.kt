package com.badmintontracker.analysis.corpus

import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.rally.Rally
import com.badmintontracker.analysis.shuttle.ShuttleSample
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class CloudClip(val rallyIndex: Int, val startTimestamp: Double, val endTimestamp: Double)

data class CorpusEntry(
    val name: String,
    val fps: Double,
    val totalFrames: Int,
    val videoWidth: Int,
    val videoHeight: Int,
    /** The cloud's FILTERED track, which feeds its gradient detector. */
    val shuttlePositions: Map<Int, ShuttleSample>,
    /**
     * The cloud's per-frame TrackNet/YOLO fusion track, which feeds its
     * shot-gap detector. Far less filtered than [shuttlePositions] and a
     * different shape in results.json, projected out of skeleton_frames by
     * trim_corpus.py. Empty for a phase1 capture, which has no skeleton data.
     */
    val fusionTrack: Map<Int, ShuttleSample>,
    val cloudRallies: List<Rally>,
    val cloudClips: List<CloudClip>,
    val keypoints: CourtKeypoints?,
    /**
     * What badminton-tracker's own detectors produce for this fixture, stage
     * by stage, as recorded by tools/corpus/make_stage_goldens.py. Empty when
     * the fixture has no stages.json.
     */
    val stages: Map<String, List<Pair<Int, Int>>>,
    /** Which badminton-tracker commit produced [stages]. Null when absent. */
    val stagesTrackerCommit: String?,
)

/** Reads one fixture file, or null when the fixture is not present. */
expect fun readFixtureFileOrNull(name: String, file: String): String?

/**
 * Reads a test resource as bytes, or null when absent.
 *
 * Separate from the text reader because the shuttle vectors are float32
 * heatmaps: decoding them as a string would corrupt every value that happens
 * not to be valid UTF-8.
 */
expect fun readResourceBytesOrNull(path: String): ByteArray?

/**
 * Whether a missing corpus should fail rather than skip.
 *
 * Off by default so the suite runs on a checkout with no captured corpus,
 * which is every checkout until someone runs tools/corpus/fetch_corpus.py.
 * Set ANALYSIS_REQUIRE_CORPUS=1 in an environment that is supposed to have
 * one, so the golden tests cannot quietly pass by not running.
 */
expect fun corpusIsRequired(): Boolean

/** Null when the fixture is absent, so a caller can skip loudly rather than fail. */
fun loadCorpusEntryOrNull(name: String): CorpusEntry? {
    val resultsText = readFixtureFileOrNull(name, "results.json") ?: return null
    val videoText = readFixtureFileOrNull(name, "video.json") ?: return null
    val clipsText = readFixtureFileOrNull(name, "clips.json") ?: return null

    val stagesJson = readFixtureFileOrNull(name, "stages.json")
        ?.let { Json.parseToJsonElement(it).jsonObject }
    val results = Json.parseToJsonElement(resultsText).jsonObject
    val video = Json.parseToJsonElement(videoText).jsonObject
    val clips = Json.parseToJsonElement(clipsText).jsonArray

    val fusion = results["fusion_shuttle_track"]?.jsonObject.orEmpty().entries.associate { (k, v) ->
        val o = v.jsonObject
        k.toInt() to ShuttleSample(
            x = o["x"]!!.jsonPrimitive.double,
            y = o["y"]!!.jsonPrimitive.double,
            visible = true,
        )
    }

    val shuttle = results["shuttle_positions"]?.jsonObject.orEmpty().entries.associate { (k, v) ->
        val o = v.jsonObject
        k.toInt() to ShuttleSample(
            x = o["x"]!!.jsonPrimitive.double,
            y = o["y"]!!.jsonPrimitive.double,
            visible = o["visible"]!!.jsonPrimitive.boolean,
        )
    }

    val rallies = results["rallies"]?.jsonArray.orEmpty().mapIndexed { i, e ->
        val o = e.jsonObject
        Rally(
            id = o["id"]?.jsonPrimitive?.int ?: (i + 1),
            startFrame = o["start_frame"]!!.jsonPrimitive.int,
            endFrame = o["end_frame"]!!.jsonPrimitive.int,
            startTimestamp = o["start_timestamp"]!!.jsonPrimitive.double,
            endTimestamp = o["end_timestamp"]!!.jsonPrimitive.double,
            durationSeconds = o["duration_seconds"]!!.jsonPrimitive.double,
        )
    }

    val keypointsRaw = video["manual_court_keypoints"]?.takeIf { it !is JsonNull }
        ?.jsonObject?.entries?.associate { (k, v) ->
            k to v.jsonArray.map { it.jsonPrimitive.double }
        }

    return CorpusEntry(
        name = name,
        fps = results["fps"]!!.jsonPrimitive.double,
        totalFrames = results["total_frames"]!!.jsonPrimitive.int,
        videoWidth = results["video_width"]?.jsonPrimitive?.int ?: 1920,
        videoHeight = results["video_height"]?.jsonPrimitive?.int ?: 1080,
        shuttlePositions = shuttle,
        fusionTrack = fusion,
        cloudRallies = rallies,
        cloudClips = clips.map {
            val o = it.jsonObject
            CloudClip(
                rallyIndex = o["rally_index"]!!.jsonPrimitive.int,
                startTimestamp = o["start_timestamp"]!!.jsonPrimitive.double,
                endTimestamp = o["end_timestamp"]!!.jsonPrimitive.double,
            )
        },
        keypoints = keypointsRaw?.let { CourtKeypoints.fromMap(it) },
        stages = stagesJson?.get("stages")?.jsonObject.orEmpty()
            .mapValues { (_, v) ->
                v.jsonArray.map { pair ->
                    val a = pair.jsonArray
                    a[0].jsonPrimitive.int to a[1].jsonPrimitive.int
                }
            },
        stagesTrackerCommit = stagesJson?.get("provenance")?.jsonObject
            ?.get("tracker_commit")?.jsonPrimitive?.content,
    )
}

fun loadCorpusEntry(name: String): CorpusEntry =
    loadCorpusEntryOrNull(name)
        ?: error("fixture 'corpus/$name' not found. Run tools/corpus/fetch_corpus.py then trim_corpus.py.")

/**
 * Run [block] against the named fixture, or skip loudly when it is absent.
 *
 * Absent is the normal case: the JVM run has fixtures only after someone has
 * captured a corpus, and Native never has them at all. Skipping keeps those
 * runs honest - the alternative, a test that passes because it did no work,
 * reads exactly like a test that passed because the port is correct. Set
 * ANALYSIS_REQUIRE_CORPUS=1 wherever a corpus is expected and the skip
 * becomes a failure.
 */
fun withCorpus(name: String, block: (CorpusEntry) -> Unit) {
    val entry = loadCorpusEntryOrNull(name)
    if (entry == null) {
        val message =
            "SKIPPED: corpus fixture 'corpus/$name' is not present, so this " +
                "comparison did not run. Capture one with tools/corpus/fetch_corpus.py " +
                "then tools/corpus/trim_corpus.py."
        if (corpusIsRequired()) error(message)
        println(message)
        return
    }
    block(entry)
}

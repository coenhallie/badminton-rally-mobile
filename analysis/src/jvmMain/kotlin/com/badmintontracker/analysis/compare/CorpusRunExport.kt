package com.badmintontracker.analysis.compare

import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.player.Coco
import com.badmintontracker.analysis.player.selectPlayers
import com.badmintontracker.analysis.raw.RawInference
import com.badmintontracker.analysis.raw.RawInferenceCodec
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/**
 * What a phone makes of one local cloud run, for the tools that build and
 * audit the two-player references (design
 * `docs/plans/2026-09-29-two-player-tracking-design.md` §3.2).
 *
 * Two things, both read exactly as the phone reads them so the references
 * stand on the phone's own view of the file:
 *
 *  - per pose-file frame, its timestamp and the camera `selectPlayers` uses
 *    there (`camera.at(timestamp)`; null when the run did not follow the
 *    camera, or lost it at that frame). The reference oracle maps its own
 *    detections through the same matrix, so the two agree on where the court
 *    is at every frame;
 *  - per side, the phone's pick: frame, court position, and the ankle
 *    midpoint in frame pixels, which is what the audit montages draw.
 *
 * Usage: CorpusRunExport <corpus dir> [pose file, default cloud-local/poses.raw] [out]
 * Writes `frames.json` beside the pose file unless [out] is given.
 */
object CorpusRunExport {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty() || args.size > 3) {
            System.err.println("usage: CorpusRunExport <corpus dir> [pose file] [out]")
            kotlin.system.exitProcess(2)
        }
        val dir = File(args[0])
        val poses = File(args.getOrNull(1) ?: File(dir, "cloud-local/poses.raw").path)
        val out = File(args.getOrNull(2) ?: File(poses.parentFile, poses.nameWithoutExtension + "-frames.json").path)
        val marks = readCorpusMarks(dir)
        val raw = RawInferenceCodec.decode(poses.readBytes())
        out.writeText(exportRun(raw, marks).toString())
        println("wrote $out")
    }
}

/** The marks a corpus directory's runs were made with: `marks_ideal.json`, else `video.json`. */
fun readCorpusMarks(dir: File): CourtKeypoints {
    val file = listOf(File(dir, "marks_ideal.json"), File(dir, "video.json")).firstOrNull { it.isFile }
        ?: error("$dir has neither marks_ideal.json nor video.json")
    val points = kotlinx.serialization.json.Json.parseToJsonElement(file.readText()).jsonObject
        .getValue("manual_court_keypoints").jsonObject
        // marked_at_s rides along with the twelve points and is not one.
        .filterValues { it is kotlinx.serialization.json.JsonArray }
        .mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.double } }
    return CourtKeypoints.fromMap(points) ?: error("$file does not hold all 12 marks")
}

fun exportRun(raw: RawInference, marks: CourtKeypoints): JsonObject {
    val selections = selectPlayers(raw, marks)
    return buildJsonObject {
        // The marking frame's time, so a tool rebuilding the camera for
        // another pose file of the same video (the device-style file) keeps it.
        put("cameraReferenceTimestamp", raw.camera?.referenceTimestamp?.let { JsonPrimitive(it) } ?: JsonNull)
        putJsonArray("frames") {
            for (frame in raw.frames) {
                add(
                    buildJsonObject {
                        put("k", frame.frame)
                        put("timestamp", frame.timestamp)
                        put("persons", frame.persons.size)
                        val m = raw.camera?.at(frame.timestamp)
                        if (m == null) {
                            put("camera", JsonNull)
                        } else {
                            put("camera", buildJsonArray { m.forEach { row -> add(buildJsonArray { row.forEach { add(JsonPrimitive(it)) } }) } })
                        }
                    },
                )
            }
        }
        put(
            "sides",
            buildJsonObject {
                for (selection in selections) {
                    put(
                        selection.side.name,
                        buildJsonObject {
                            put("framesWithPose", selection.track.framesWithPose)
                            put(
                                "rejections",
                                buildJsonObject { selection.track.rejections.forEach { (r, n) -> put(r.name, n) } },
                            )
                            putJsonArray("samples") {
                                // One pose per sample, at the same frame (PlayerSelection's contract).
                                selection.track.samples.zip(selection.poses).forEach { (sample, pose) ->
                                    val l = pose.keypoints[Coco.LEFT_ANKLE]
                                    val r = pose.keypoints[Coco.RIGHT_ANKLE]
                                    add(
                                        buildJsonObject {
                                            put("frame", sample.frame)
                                            put("x", sample.courtPosition.x)
                                            put("y", sample.courtPosition.y)
                                            putJsonArray("ankle") {
                                                add(JsonPrimitive((l.x + r.x) / 2.0))
                                                add(JsonPrimitive((l.y + r.y) / 2.0))
                                            }
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            },
        )
    }
}

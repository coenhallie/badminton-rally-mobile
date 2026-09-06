package com.badmintontracker.analysis.result

import com.badmintontracker.analysis.rally.Rally
import com.badmintontracker.analysis.shuttle.ShuttleSample
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test

class AnalysisResultTest {

    private val result = AnalysisResult(
        rallies = listOf(
            Rally(1, 27, 132, 0.9, 4.4, 3.5).serialized(),
            Rally(2, 300, 432, 10.0, 14.4, 4.4).serialized(),
        ),
        shuttlePositions = mapOf(
            "0" to ShuttleSample(100.5, 200.25, visible = true).serialized(),
            "1" to ShuttleSample(0.0, 0.0, visible = false).serialized(),
        ),
        fps = 30.0,
        totalFrames = 600,
        videoMetadata = VideoMetadata(durationSeconds = 20.0, filename = "match.mp4"),
    )

    @Test
    fun the_top_level_keys_are_exactly_the_ones_the_cloud_writes() {
        // modal_supabase_processor.py:4189-4200. A camelCase leak or an extra
        // key here means the comparator diffs nothing and the web app reads
        // nothing, and neither failure is loud.
        Json.parseToJsonElement(result.toJson()).jsonObject.keys shouldBe setOf(
            "phase", "pipeline_variant", "rallies", "shuttle_positions",
            "fps", "total_frames", "video_metadata",
        )
    }

    @Test
    fun the_phase_and_variant_are_the_only_ones_this_pipeline_produces() {
        // legacy, not gb_fusion: section 8 puts the fusion variant out of scope.
        val o = Json.parseToJsonElement(result.toJson()).jsonObject
        o["phase"]!!.jsonPrimitive.content shouldBe "phase1"
        o["pipeline_variant"]!!.jsonPrimitive.content shouldBe "legacy"
    }

    @Test
    fun shuttle_positions_are_keyed_by_frame_number_as_a_string() {
        val positions = Json.parseToJsonElement(result.toJson()).jsonObject["shuttle_positions"]!!.jsonObject
        positions.keys shouldBe setOf("0", "1")
        val first = positions["0"]!!.jsonObject
        first.keys shouldBe setOf("x", "y", "visible")
        first["x"]!!.jsonPrimitive.content shouldBe "100.5"
    }

    @Test
    fun rally_fields_are_snake_case() {
        val rally = Json.parseToJsonElement(result.toJson()).jsonObject["rallies"]!!
            .let { Json.parseToJsonElement(it.toString()) }
        val first = rally.toString()
        listOf("start_frame", "end_frame", "start_timestamp", "end_timestamp", "duration_seconds")
            .forEach { (first.contains("\"$it\"")) shouldBe true }
        listOf("startFrame", "endFrame", "startTimestamp").forEach {
            (first.contains("\"$it\"")) shouldBe false
        }
    }

    @Test
    fun skeleton_data_is_absent_by_default_and_present_in_ab_mode() {
        // Section 5.5: omitted by default, since uploading the per-frame blob
        // is the payload problem inverted. The web app branches on the KEY, so
        // an explicit null would not do - assert absence.
        val default = Json.parseToJsonElement(result.toJson()).jsonObject
        default.containsKey("skeleton_data") shouldBe false

        val ab = Json.parseToJsonElement(result.copy(skeletonData = emptyList()).toJson()).jsonObject
        ab.containsKey("skeleton_data") shouldBe true
    }

    @Test
    fun it_round_trips_through_its_own_parser() {
        AnalysisResult.fromJson(result.toJson()) shouldBe result
    }

    @Test
    fun video_metadata_carries_the_two_keys_the_worker_writes() {
        val meta = Json.parseToJsonElement(result.toJson()).jsonObject["video_metadata"]!!.jsonObject
        meta.keys shouldBe setOf("duration_seconds", "filename")
    }
}

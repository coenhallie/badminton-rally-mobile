package com.badmintontracker.analysis.compare

import com.badmintontracker.analysis.rally.Rally
import com.badmintontracker.analysis.result.AnalysisResult
import com.badmintontracker.analysis.result.SerializedShuttle
import com.badmintontracker.analysis.result.VideoMetadata
import com.badmintontracker.analysis.result.serialized
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test

class ComparatorTest {

    private fun result(
        shuttle: Map<Int, Triple<Double, Double, Boolean>>,
        rallies: List<Rally>,
    ) = AnalysisResult(
        rallies = rallies.map { it.serialized() },
        shuttlePositions = shuttle.entries.associate { (f, v) ->
            f.toString() to SerializedShuttle(v.first, v.second, v.third)
        },
        fps = 30.0,
        totalFrames = 600,
        videoMetadata = VideoMetadata(20.0, "match.mp4"),
    )

    private fun rally(id: Int, start: Double, end: Double) =
        Rally(id, (start * 30).toInt(), (end * 30).toInt(), start, end, end - start)

    private val visibleTrack = (0 until 10).associateWith {
        Triple(100.0 + it, 200.0 + it, true)
    }

    @Test
    fun identical_inputs_report_perfect_agreement() {
        val r = result(visibleTrack, listOf(rally(1, 1.0, 5.0), rally(2, 10.0, 14.0)))
        val report = compare(local = r, cloud = r)
        report.level1.bothVisible shouldBe 10
        report.level1.localOnly shouldBe 0
        report.level1.cloudOnly shouldBe 0
        report.level1.medianPixelDelta shouldBe 0.0
        report.level2.matched shouldBe 2
        report.level2.unmatchedLocal shouldBe emptyList()
        report.level2.unmatchedCloud shouldBe emptyList()
    }

    @Test
    fun a_known_offset_is_measured_not_merely_detected() {
        // Asserting "greater than zero" cannot tell a 3-pixel decode
        // difference from a 300-pixel coordinate-space bug, and those need
        // completely different responses.
        val cloud = result(visibleTrack, emptyList())
        val local = result(
            visibleTrack.mapValues { (_, v) -> Triple(v.first + 3.0, v.second, v.third) },
            emptyList(),
        )
        val report = compare(local = local, cloud = cloud)
        report.level1.medianPixelDelta shouldBe 3.0
        report.level1.p95PixelDelta shouldBe 3.0
    }

    @Test
    fun visibility_disagreement_is_bucketed_by_direction() {
        // Collapsing these into one number hides which side over-detects,
        // which is the whole diagnostic value of level 1.
        val cloud = result(
            mapOf(0 to Triple(1.0, 1.0, true), 1 to Triple(0.0, 0.0, false), 2 to Triple(3.0, 3.0, true)),
            emptyList(),
        )
        val local = result(
            mapOf(0 to Triple(1.0, 1.0, true), 1 to Triple(2.0, 2.0, true), 2 to Triple(0.0, 0.0, false)),
            emptyList(),
        )
        val report = compare(local = local, cloud = cloud)
        report.level1.bothVisible shouldBe 1
        report.level1.localOnly shouldBe 1
        report.level1.cloudOnly shouldBe 1
        report.level1.neither shouldBe 0
    }

    @Test
    fun an_unmatched_rally_on_each_side_is_reported_in_the_right_list() {
        val cloud = result(visibleTrack, listOf(rally(1, 1.0, 5.0), rally(2, 30.0, 34.0)))
        val local = result(visibleTrack, listOf(rally(1, 1.0, 5.0), rally(2, 50.0, 54.0)))
        val report = compare(local = local, cloud = cloud)
        report.level2.matched shouldBe 1
        report.level2.unmatchedLocal.map { it.startTimestamp } shouldBe listOf(50.0)
        report.level2.unmatchedCloud.map { it.startTimestamp } shouldBe listOf(30.0)
    }

    @Test
    fun matched_rally_bound_deltas_are_measured() {
        val cloud = result(visibleTrack, listOf(rally(1, 1.0, 5.0)))
        val local = result(visibleTrack, listOf(rally(1, 1.5, 5.25)))
        val report = compare(local = local, cloud = cloud)
        report.level2.matched shouldBe 1
        report.level2.medianStartDeltaSeconds shouldBe 0.5
        report.level2.medianEndDeltaSeconds shouldBe 0.25
    }

    @Test
    fun an_empty_overlap_yields_null_rather_than_zero() {
        // Zero would read as perfect agreement, which is the opposite of what
        // "the two sides never both saw the shuttle" means.
        val cloud = result(mapOf(0 to Triple(1.0, 1.0, true)), emptyList())
        val local = result(mapOf(0 to Triple(0.0, 0.0, false)), emptyList())
        val report = compare(local = local, cloud = cloud)
        report.level1.bothVisible shouldBe 0
        report.level1.medianPixelDelta shouldBe null
        report.level1.p95PixelDelta shouldBe null
        report.level2.medianStartDeltaSeconds shouldBe null
    }

    @Test
    fun the_report_serialises_with_stable_keys() {
        // Section 8: cross-video statistics are a tool over exported reports,
        // so the export is an interface.
        val r = result(visibleTrack, listOf(rally(1, 1.0, 5.0)))
        val json = Json.parseToJsonElement(compare(local = r, cloud = r).toJson()).jsonObject
        json.keys shouldBe setOf("level1", "level2")
        json["level1"]!!.jsonObject.keys shouldBe setOf(
            "both_visible", "local_only", "cloud_only", "neither",
            "median_pixel_delta", "p95_pixel_delta",
        )
        json["level2"]!!.jsonObject.keys shouldBe setOf(
            "local_count", "cloud_count", "matched",
            "median_start_delta_seconds", "median_end_delta_seconds",
            "unmatched_local", "unmatched_cloud",
        )
    }
}

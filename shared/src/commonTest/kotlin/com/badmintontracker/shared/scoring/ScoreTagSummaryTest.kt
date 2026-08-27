package com.badmintontracker.shared.scoring

import com.badmintontracker.shared.model.LabelCount
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ScoreTagSummaryTest {

    private fun stateWith(vararg tagsPerPoint: List<PointTag>): MatchState {
        val points = tagsPerPoint.indices.map { ScoreEvent.PointTo(Side.HOME) }
        val tags = tagsPerPoint.mapIndexed { i, t -> ScoreEvent.TagPoint(i, t, null) }
        return foldMatchState(
            ScoringRules.BWF_21,
            MatchSetup(doubles = false, firstServer = Side.HOME),
            points + tags,
        )
    }

    private fun tag(name: String, color: String? = "green") = listOf(PointTag(name, color))

    @Test
    fun an_untagged_match_has_an_empty_tally() {
        val summary = buildScoreTagSummary(stateWith(emptyList(), emptyList()))
        summary.taggedPointCount shouldBe 0
        summary.labels.shouldBeEmpty()
        summary.isEmpty shouldBe true
    }

    @Test
    fun tags_are_counted_and_ordered_by_how_often_they_were_used() {
        val summary = buildScoreTagSummary(
            stateWith(
                tag("Forced error", "amber"),
                tag("Good shot"),
                tag("Forced error", "amber"),
                tag("Forced error", "amber"),
            )
        )
        summary.taggedPointCount shouldBe 4
        summary.labels shouldBe listOf(
            LabelCount(name = "Forced error", colorKey = "amber", count = 3, sharePercent = 75),
            LabelCount(name = "Good shot", colorKey = "green", count = 1, sharePercent = 25),
        )
    }

    @Test
    fun one_point_carrying_two_tags_counts_once_for_each() {
        val summary = buildScoreTagSummary(
            stateWith(listOf(PointTag("Good shot", "green"), PointTag("Forced error", "amber")))
        )
        // The point count is points; the label counts are labels. A rally can be
        // both a good shot and a forced error, and both belong in the tally.
        summary.taggedPointCount shouldBe 1
        summary.labels.map { it.count } shouldBe listOf(1, 1)
    }

    @Test
    fun labels_group_case_insensitively_and_the_newest_spelling_wins() {
        // Same rule as buildMatchLabelSummary: the name is a snapshot, so it is the
        // only identity available, and folding case matches what the database
        // enforces on the live palette.
        val summary = buildScoreTagSummary(
            stateWith(tag("forced error", "amber"), tag("Forced Error", "red"))
        )
        summary.labels shouldBe listOf(
            LabelCount(name = "Forced Error", colorKey = "red", count = 2, sharePercent = 100),
        )
    }

    @Test
    fun a_blank_tag_name_is_not_a_tag() {
        buildScoreTagSummary(stateWith(listOf(PointTag("   ", "green")))).isEmpty shouldBe true
    }

    @Test
    fun ties_break_the_same_way_the_clip_summary_breaks_them() {
        // The assertion that matters most in this file. Two labels used equally
        // often must come out in the same order here as on the rally page, or the
        // same match reads two ways in two places.
        val summary = buildScoreTagSummary(tagsOf("Zebra", "Alpha"))
        summary.labels.map { it.name } shouldBe listOf("Alpha", "Zebra")
    }

    private fun tagsOf(vararg names: String) =
        stateWith(*names.map { listOf(PointTag(it, "green")) }.toTypedArray())

    @Test
    fun shares_round_rather_than_truncate() {
        // Same reason the clip summary rounds: an integer division would print 16
        // beside a bar drawn at 17.
        val summary = buildScoreTagSummary(tagsOf("A", "A", "B", "B", "B", "C"))
        summary.labels.map { it.sharePercent } shouldBe listOf(50, 33, 17)
    }
}

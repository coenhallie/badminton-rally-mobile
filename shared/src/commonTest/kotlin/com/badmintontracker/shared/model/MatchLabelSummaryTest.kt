package com.badmintontracker.shared.model

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlin.test.Test

class MatchLabelSummaryTest {

    private fun clip(id: String, rallyIndex: Int) = RallyClip(
        id = id, videoId = "v1", ownerId = "u", rallyIndex = rallyIndex,
        startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
        clipStoragePath = "p/$id.mp4", thumbnailStoragePath = null,
        title = null, annotationCount = 0,
        createdAt = Instant.parse("2026-08-26T12:00:00Z"),
    )

    private fun note(
        id: String,
        clipId: String,
        label: String?,
        color: String? = "green",
        body: String = "",
        createdAt: String = "2026-08-26T12:00:00Z",
    ) = RallyAnnotation(
        id = id, clipId = clipId, timestampSeconds = 1f, body = body,
        labelName = label, labelColor = if (label == null) null else color,
        createdAt = Instant.parse(createdAt),
    )

    private val twoClips = listOf(clip("c1", 1), clip("c2", 2))

    @Test
    fun body_only_notes_are_not_counted() {
        val summary = buildMatchLabelSummary(
            twoClips,
            listOf(
                note("a1", "c1", label = null, body = "wrong footwork"),
                note("a2", "c1", label = "   ", body = "blank label"),
                note("a3", "c1", label = "Good shot"),
            ),
        )

        summary.labelledNoteCount shouldBe 1
        summary.labels.map { it.name } shouldBe listOf("Good shot")
    }

    @Test
    fun casing_and_whitespace_fold_into_one_label() {
        val summary = buildMatchLabelSummary(
            twoClips,
            listOf(
                note("a1", "c1", "Good shot"),
                note("a2", "c1", "good shot"),
                note("a3", "c2", "  Good shot  "),
            ),
        )

        summary.labels.size shouldBe 1
        summary.labels[0].count shouldBe 3
        summary.labelledNoteCount shouldBe 3
    }

    @Test
    fun display_name_and_colour_come_from_the_most_recent_annotation() {
        val summary = buildMatchLabelSummary(
            twoClips,
            listOf(
                note("a1", "c1", "Good shot", color = "green", createdAt = "2026-08-26T12:00:00Z"),
                note("a2", "c1", "Good Shot", color = "teal", createdAt = "2026-08-26T13:00:00Z"),
            ),
        )

        summary.labels[0].name shouldBe "Good Shot"
        summary.labels[0].colorKey shouldBe "teal"
    }

    @Test
    fun a_tie_on_created_at_breaks_on_id_so_the_colour_is_never_row_order_dependent() {
        val sameInstant = "2026-08-26T12:00:00Z"
        val summary = buildMatchLabelSummary(
            twoClips,
            listOf(
                note("a2", "c1", "Good shot", color = "teal", createdAt = sameInstant),
                note("a1", "c1", "Good shot", color = "green", createdAt = sameInstant),
            ),
        )

        summary.labels[0].colorKey shouldBe "teal"
    }

    @Test
    fun labels_sort_by_count_then_by_name() {
        val summary = buildMatchLabelSummary(
            twoClips,
            listOf(
                note("a1", "c1", "Zebra"), note("a2", "c1", "Zebra"),
                note("a3", "c1", "alpha"), note("a4", "c1", "alpha"),
                note("a5", "c2", "Mid"), note("a6", "c2", "Mid"), note("a7", "c2", "Mid"),
            ),
        )

        summary.labels.map { it.name } shouldBe listOf("Mid", "alpha", "Zebra")
    }

    @Test
    fun share_percent_rounds_half_up_and_is_not_normalised_to_a_hundred() {
        val summary = buildMatchLabelSummary(
            twoClips,
            listOf(
                note("a1", "c1", "Four"), note("a2", "c1", "Four"),
                note("a3", "c1", "Four"), note("a4", "c1", "Four"),
                note("a5", "c2", "One"),
                note("a6", "c2", "Other"),
            ),
        )

        summary.labels.map { it.sharePercent } shouldBe listOf(67, 17, 17)
    }

    @Test
    fun top_rally_is_the_clip_with_the_most_labelled_notes() {
        val summary = buildMatchLabelSummary(
            twoClips,
            listOf(
                note("a1", "c1", "Good shot"),
                note("a2", "c2", "Good shot"),
                note("a3", "c2", "Unforced error", color = "red"),
            ),
        )

        summary.topRally shouldBe TopRally(clipId = "c2", rallyIndex = 2, labelCount = 2)
    }

    @Test
    fun a_tie_on_label_count_breaks_on_the_lower_rally_index() {
        val summary = buildMatchLabelSummary(
            listOf(clip("c1", 5), clip("c2", 2)),
            listOf(
                note("a1", "c1", "Good shot"),
                note("a2", "c2", "Good shot"),
            ),
        )

        summary.topRally!!.clipId shouldBe "c2"
    }

    @Test
    fun annotations_for_clips_outside_the_match_are_ignored() {
        val summary = buildMatchLabelSummary(
            twoClips,
            listOf(
                note("a1", "c1", "Good shot"),
                note("a2", "pruned-clip", "Good shot"),
            ),
        )

        summary.labelledNoteCount shouldBe 1
        summary.labels.sumOf { it.count } shouldBe summary.labelledNoteCount
    }

    @Test
    fun empty_input_is_an_empty_summary_not_a_null() {
        val summary = buildMatchLabelSummary(emptyList(), emptyList())

        summary.labelledNoteCount shouldBe 0
        summary.labels.shouldBeEmpty()
        summary.topRally.shouldBeNull()
        summary.isEmpty shouldBe true
    }

    @Test
    fun strip_label_name_leaves_short_names_alone_and_truncates_long_ones() {
        stripLabelName("Unforced error") shouldBe "Unforced error"
        stripLabelName("Backhand clear too short") shouldBe "Backhand clear…"
    }
}

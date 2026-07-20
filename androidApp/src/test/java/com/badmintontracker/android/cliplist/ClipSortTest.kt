package com.badmintontracker.android.cliplist

import com.badmintontracker.shared.model.RallyClip
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlin.test.Test

class ClipSortTest {
    private fun clip(id: String, rallyIndex: Int, notes: Int) = RallyClip(
        id = id, videoId = "v", ownerId = "me", rallyIndex = rallyIndex,
        startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
        clipStoragePath = "p/$id.mp4", thumbnailStoragePath = null,
        title = null, annotationCount = notes,
        createdAt = Instant.parse("2026-05-04T12:00:00Z"),
    )

    @Test
    fun rally_order_sorts_by_rally_index() {
        val sorted = sortClips(listOf(clip("b", 2, 5), clip("a", 1, 0)), ClipSort.RallyOrder)
        sorted.map { it.id } shouldBe listOf("a", "b")
    }

    @Test
    fun most_notes_sorts_descending_with_rally_index_tiebreak() {
        val sorted = sortClips(
            listOf(clip("c", 3, 1), clip("a", 1, 1), clip("b", 2, 3)),
            ClipSort.MostNotes,
        )
        sorted.map { it.id } shouldBe listOf("b", "a", "c")
    }
}

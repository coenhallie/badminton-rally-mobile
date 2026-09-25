package com.badmintontracker.shared.matchlist

import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.model.RallyClip
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test

/**
 * [MatchList.confirmationTarget]: an open confirmation keeps its row only while
 * the row, as the list draws it now, still offers what the dialog confirms. A
 * row that has gone, or that stopped offering the action (a device run started
 * and now holds the file), closes the dialog for good.
 */
class MatchListConfirmationTest {
    private val t = Instant.parse("2026-09-08T10:00:00Z")

    private fun entry(id: String, stage: AnalyzeStage) = LocalVideoEntry(
        id = id, uri = "content://x/$id", displayName = "$id.mp4", durationMs = 48_000, sizeBytes = 10,
        addedAtEpochMs = t.toEpochMilliseconds(), stage = stage,
    )

    private fun clip(videoId: String, owner: String = "me") = RallyClip(
        id = "$videoId-1", videoId = videoId, ownerId = owner, rallyIndex = 1,
        startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
        clipStoragePath = "clips/$videoId/1.mp4", annotationCount = 0, createdAt = t,
    )

    private fun list(
        entries: List<LocalVideoEntry> = emptyList(),
        clips: List<RallyClip> = emptyList(),
        runs: Map<String, DeviceRun> = emptyMap(),
    ) = buildMatchList(
        MatchListInput(
            clips = clips, currentUserId = "me", sharerByVideoId = emptyMap(), metadataByVideoId = emptyMap(),
            scoreLogs = emptyList(), localEntries = entries, cloudProgress = emptyMap(), deviceRuns = runs,
            storedTrackIds = emptySet(), storedSkeletonIds = emptySet(), deviceClipCounts = emptyMap(),
        ),
        TimeZone.UTC,
    )

    private val running = mapOf("v1" to DeviceRun(DeviceRunKind.RUNNING))

    @Test
    fun a_row_that_still_offers_the_action_keeps_its_confirmation() {
        val both = list(entries = listOf(entry("v1", AnalyzeStage.ANALYZED)), clips = listOf(clip("v1")))
        MatchConfirmation.entries.forEach { kind ->
            both.confirmationTarget("video-v1", kind)?.key shouldBe "video-v1"
        }
    }

    @Test
    fun a_row_that_left_the_list_closes_every_confirmation() {
        MatchConfirmation.entries.forEach { kind -> list().confirmationTarget("video-v1", kind) shouldBe null }
    }

    /**
     * A run started under the open dialog: the file is held, so removing it,
     * and deleting the match (which discards the file), are no longer offered.
     */
    @Test
    fun a_run_that_starts_under_the_dialog_closes_what_it_no_longer_allows() {
        val held = list(entries = listOf(entry("v1", AnalyzeStage.ANALYZED)), clips = listOf(clip("v1")), runs = running)
        held.itemWithKey("video-v1")?.delete shouldBe MatchDelete.NONE
        MatchConfirmation.entries.forEach { kind -> held.confirmationTarget("video-v1", kind) shouldBe null }
    }

    @Test
    fun the_choice_needs_both_a_file_to_remove_and_a_match_to_delete() {
        val cloudOnly = list(clips = listOf(clip("v1")))
        cloudOnly.confirmationTarget("video-v1", MatchConfirmation.DELETE)?.key shouldBe "video-v1"
        cloudOnly.confirmationTarget("video-v1", MatchConfirmation.CHOICE) shouldBe null
        cloudOnly.confirmationTarget("video-v1", MatchConfirmation.REMOVE) shouldBe null

        val phoneOnly = list(entries = listOf(entry("v1", AnalyzeStage.LOCAL)))
        phoneOnly.confirmationTarget("video-v1", MatchConfirmation.REMOVE)?.key shouldBe "video-v1"
        phoneOnly.confirmationTarget("video-v1", MatchConfirmation.CHOICE) shouldBe null
        phoneOnly.confirmationTarget("video-v1", MatchConfirmation.DELETE) shouldBe null
    }

    @Test
    fun leaving_a_share_is_a_delete_confirmation_but_never_the_choice() {
        val shared = list(clips = listOf(clip("v1", owner = "someone-else")))
        shared.confirmationTarget("video-v1", MatchConfirmation.DELETE)?.delete shouldBe MatchDelete.LEAVE_SHARE
        shared.confirmationTarget("video-v1", MatchConfirmation.CHOICE) shouldBe null
    }
}

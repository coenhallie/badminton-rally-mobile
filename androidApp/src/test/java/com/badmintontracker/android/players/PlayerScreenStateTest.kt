package com.badmintontracker.android.players

import com.badmintontracker.android.cliplist.listedMatches
import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.matchlist.MatchList
import com.badmintontracker.shared.matchlist.MatchListInput
import com.badmintontracker.shared.matchlist.buildMatchList
import com.badmintontracker.shared.model.MatchMetadata
import com.badmintontracker.shared.model.RallyClip
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlin.test.Test

/** What the player page works out outside its composables. */
class PlayerScreenStateTest {

    private val me = "user-me"
    private val at = Instant.parse("2026-10-28T10:00:00Z")

    private fun entry(id: String, homePlayerId: String?) = LocalVideoEntry(
        id = id, uri = "content://x/$id", displayName = "$id.mp4",
        durationMs = 48_000, sizeBytes = 10, addedAtEpochMs = at.toEpochMilliseconds(),
        stage = AnalyzeStage.LOCAL, homePlayerId = homePlayerId,
    )

    /** A match someone else shared with this coach, its video naming Marco's id all the same. */
    private fun sharedClip() = RallyClip(
        id = "shared-1", videoId = "shared", ownerId = "user-other", rallyIndex = 1,
        startTimestamp = 0f, endTimestamp = 1f, durationSeconds = 1f,
        clipStoragePath = "clips/shared/1.mp4", thumbnailStoragePath = null,
        title = null, annotationCount = 0, createdAt = at,
    )

    private fun list(): MatchList = buildMatchList(
        MatchListInput(
            clips = listOf(sharedClip()), currentUserId = me, sharerByVideoId = mapOf("shared" to "Ana"),
            metadataByVideoId = mapOf("shared" to MatchMetadata("shared", homePlayerId = "p-marco")),
            scoreLogs = emptyList(),
            localEntries = listOf(entry("marcos", "p-marco"), entry("someone-elses", "p-li")),
            cloudProgress = emptyMap(), deviceRuns = emptyMap(),
            storedTrackIds = emptySet(), storedSkeletonIds = emptySet(), deviceClipCounts = emptyMap(),
            localNoteCounts = emptyMap(), players = emptyList(),
        ),
    )

    @Test
    fun all_matches_lists_everything() {
        val full = list()
        full.shared shouldHaveSize 1

        listedMatches(full, playerId = null) shouldBe full
    }

    @Test
    fun a_players_list_is_their_matches_and_nothing_shared() {
        val listed = listedMatches(list(), playerId = "p-marco")

        listed.mine.map { it.entryId } shouldContainExactly listOf("marcos")
        listed.shared.shouldBeEmpty()
    }

    @Test
    fun a_player_on_the_list_is_not_gone() {
        playerPageGone(present = true, wasPresent = true, loaded = true) shouldBe false
    }

    @Test
    fun a_player_who_leaves_the_list_is_gone() {
        // Deleted from this page, or on another device and dropped by a refresh.
        playerPageGone(present = false, wasPresent = true, loaded = false) shouldBe true
    }

    @Test
    fun a_player_not_listed_yet_is_not_gone_until_the_list_has_loaded() {
        // A page restored before the players' first refresh lands.
        playerPageGone(present = false, wasPresent = false, loaded = false) shouldBe false
        playerPageGone(present = false, wasPresent = false, loaded = true) shouldBe true
    }
}

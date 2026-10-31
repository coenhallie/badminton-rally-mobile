package com.badmintontracker.android.localvideo

import com.badmintontracker.shared.players.MatchPlayers
import com.badmintontracker.shared.players.matchPlayers
import com.badmintontracker.shared.scoring.Side
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** The entry a recorded or imported video becomes, by what it was picked for. */
class NewLocalVideoEntryTest {

    private fun entry(forMatch: MatchTarget? = null, forPlayer: String? = null) = newLocalVideoEntry(
        id = "e-1", uri = "content://media/1", displayName = "shuttl_1.mp4",
        durationMs = 48_000, sizeBytes = 1_024, addedAtEpochMs = 1_000,
        forMatch = forMatch, forPlayer = forPlayer,
    )

    @Test
    fun a_video_for_a_player_makes_them_the_near_home_player() {
        val e = entry(forPlayer = "p-marco")

        e.matchPlayers shouldBe MatchPlayers("p-marco", null, emptyList(), emptyList())
        e.nearSide shouldBe Side.HOME
    }

    @Test
    fun a_video_for_nobody_names_nobody_and_no_end() {
        val e = entry()

        e.matchPlayers shouldBe MatchPlayers.NONE
        e.nearSide.shouldBeNull()
        e.scoreLogId.shouldBeNull()
        e.title.shouldBeNull()
    }

    @Test
    fun a_video_for_a_match_carries_its_log_and_name() {
        val e = entry(forMatch = MatchTarget("log-1", "Thu League"))

        e.scoreLogId shouldBe "log-1"
        e.title shouldBe "Thu League"
        e.matchPlayers shouldBe MatchPlayers.NONE
        e.nearSide.shouldBeNull()
    }

    @Test
    fun the_file_facts_are_kept() {
        val e = entry()

        e.id shouldBe "e-1"
        e.uri shouldBe "content://media/1"
        e.displayName shouldBe "shuttl_1.mp4"
        e.durationMs shouldBe 48_000
        e.sizeBytes shouldBe 1_024
        e.addedAtEpochMs shouldBe 1_000
    }
}

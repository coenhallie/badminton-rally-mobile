package com.badmintontracker.shared.players

import com.badmintontracker.analysis.player.CourtSide
import com.badmintontracker.shared.scoring.MatchFormat
import com.badmintontracker.shared.scoring.MatchSetup
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.ScoreLogStatus
import com.badmintontracker.shared.scoring.ScoringRules
import com.badmintontracker.shared.scoring.Side
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlin.test.Test

/** The match details editor's who-played: what it saves, and what crosses a singles and doubles switch. */
class DetailsPlayersDraftTest {

    private val at = Instant.parse("2026-10-31T10:00:00Z")

    private fun log(homeId: String? = null, away: List<String> = emptyList()) = ScoreLog(
        id = "log-1", videoId = null, title = "Thu League",
        homePlayers = emptyList(), awayPlayers = away,
        homePlayerId = homeId, awayPlayerId = null, format = null,
        rules = ScoringRules.BWF_21, setup = MatchSetup(firstServer = Side.HOME),
        events = emptyList(), status = ScoreLogStatus.LIVE, createdAt = at, updatedAt = at,
    )

    private val coenVsMarco = MatchPlayers(null, null, listOf("Coen"), listOf("Marco"))

    @Test
    fun a_score_log_saves_its_sides_and_the_end() {
        val draft = scoreLogPlayersDraft(log(homeId = "player-marco", away = listOf("Li")), Side.AWAY)

        draft.toAssignment(MatchFormat.SINGLES) shouldBe
            (MatchPlayers("player-marco", null, emptyList(), listOf("Li")) to Side.AWAY)
        draft.offersPairs(MatchFormat.SINGLES) shouldBe false
        // The format never reshapes a score log's sides.
        draft.withFormat(MatchFormat.DOUBLES) shouldBe draft
    }

    @Test
    fun a_video_in_singles_saves_the_section() {
        val draft = videoPlayersDraft(coenVsMarco, Side.HOME, MatchFormat.SINGLES)
        draft.ends.shouldNotBeNull()

        val placed = draft.copy(ends = draft.ends!!.withPlayer(CourtSide.NEAR, "player-marco"))
        placed.toAssignment(MatchFormat.SINGLES) shouldBe
            (MatchPlayers("player-marco", null, emptyList(), listOf("Marco")) to Side.HOME)
        placed.offersPairs(MatchFormat.SINGLES) shouldBe false
        draft.offersPairs(MatchFormat.SINGLES) shouldBe true
    }

    @Test
    fun a_video_in_doubles_is_typed_names_with_no_section() {
        val doubles = MatchPlayers(null, null, listOf("Coen", "Ana"), listOf("Marco", "Li"))
        val draft = videoPlayersDraft(doubles, Side.AWAY, MatchFormat.DOUBLES)

        draft.ends.shouldBeNull()
        draft.toAssignment(MatchFormat.DOUBLES) shouldBe (doubles to Side.AWAY)
    }

    @Test
    fun names_cross_into_doubles_and_back_without_loss() {
        // A swapped singles video: Coen typed at far, Marco at near, Away nearest the camera.
        val singles = videoPlayersDraft(coenVsMarco, Side.AWAY, MatchFormat.SINGLES)
        val withPartner = singles.copy(sides = singles.sides.withName(Side.HOME, 1, "Ana"))

        val doubles = withPartner.withFormat(MatchFormat.DOUBLES)
        doubles.ends.shouldBeNull()
        doubles.toAssignment(MatchFormat.DOUBLES) shouldBe
            (MatchPlayers(null, null, listOf("Coen", "Ana"), listOf("Marco")) to Side.AWAY)

        val back = doubles.withFormat(MatchFormat.SINGLES)
        back.ends.shouldNotBeNull()
        // Both names are on screen: Marco at near on the player's picker, Coen the far opponent.
        back.ends!!.field(CourtSide.NEAR) shouldBe EndField.SOMEONE_ELSE
        back.ends!!.nearName shouldBe "Marco"
        back.ends!!.field(CourtSide.FAR) shouldBe EndField.OPPONENT
        back.toAssignment(MatchFormat.SINGLES) shouldBe (coenVsMarco to Side.AWAY)
        // Format not set is singles too.
        doubles.withFormat(null).toAssignment(null) shouldBe (coenVsMarco to Side.AWAY)
    }

    @Test
    fun a_profile_is_never_dropped_by_a_switch_to_doubles() {
        val draft = videoPlayersDraft(MatchPlayers("player-marco", null, emptyList(), emptyList()), Side.HOME, null)

        draft.offersPairs(null) shouldBe false
        draft.withFormat(MatchFormat.DOUBLES) shouldBe draft
    }
}

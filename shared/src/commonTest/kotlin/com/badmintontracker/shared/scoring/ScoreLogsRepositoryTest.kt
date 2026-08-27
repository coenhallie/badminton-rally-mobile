package com.badmintontracker.shared.scoring

import com.russhwolf.settings.MapSettings
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours

class ScoreLogsRepositoryTest {

    private val t0 = Instant.parse("2026-08-27T18:00:00Z")

    /**
     * The internal test-seam constructor, the same shape AnnotationLabelsRepositoryImpl
     * uses. Two things need controlling: the clock, and who the cache thinks it
     * belongs to. The owner matters even in a local-only test - the cache refuses
     * to hand back a payload it cannot match to the signed-in user, so a repository
     * that reports no owner reads back nothing.
     */
    private fun repo(
        settings: MapSettings = MapSettings(),
        owner: String? = "owner-1",
        now: () -> Instant = { t0 },
    ) = ScoreLogsRepository(client = null, settings = settings, now = now, ownerId = { owner })

    private fun ScoreLogsRepository.newMatch() = create(
        title = "Thu League",
        homePlayers = listOf("Coen"),
        awayPlayers = listOf("Marco"),
        rules = ScoringRules.BWF_21,
        setup = MatchSetup(doubles = false, firstServer = Side.HOME),
    )

    @Test
    fun a_new_match_is_live_with_no_video_and_no_points() {
        val created = repo().newMatch()
        created.status shouldBe ScoreLogStatus.LIVE
        created.videoId.shouldBeNull()
        created.events.shouldBeEmpty()
        created.createdAt shouldBe t0
    }

    @Test
    fun a_new_match_is_readable_immediately_rather_than_after_a_round_trip() {
        // The coach is standing on a court. Nothing here may wait on a network.
        val repo = repo()
        val created = repo.newMatch()
        repo.logs.value.map { it.id } shouldBe listOf(created.id)
        repo.get(created.id) shouldBe created
    }

    @Test
    fun scoring_replaces_the_event_list_wholesale() {
        // Undo and reset both produce a shorter list rather than an inverse
        // operation, so the store takes the list the fold is about to be given.
        val repo = repo()
        val created = repo.newMatch()
        repo.replaceEvents(created.id, listOf(ScoreEvent.PointTo(Side.HOME)))
        repo.get(created.id)?.state()?.currentGame shouldBe SideScore(home = 1, away = 0)
        repo.replaceEvents(created.id, emptyList())
        repo.get(created.id)?.state()?.currentGame shouldBe SideScore.ZERO
    }

    @Test
    fun finishing_a_match_leaves_it_unbound_rather_than_incomplete() {
        // §7.1: a match with no video is a terminal state, not a half-done one.
        val repo = repo()
        val created = repo.newMatch()
        repo.finish(created.id)
        repo.get(created.id)?.status shouldBe ScoreLogStatus.UNBOUND
    }

    @Test
    fun renaming_trims_and_keeps_everything_else() {
        val repo = repo()
        val created = repo.newMatch()
        repo.rename(created.id, "  Club night  ")
        repo.get(created.id)?.title shouldBe "Club night"
        repo.get(created.id)?.homePlayers shouldBe listOf("Coen")
    }

    @Test
    fun mutating_a_match_that_is_gone_is_a_no_op() {
        val repo = repo()
        repo.rename("nope", "x")
        repo.replaceEvents("nope", listOf(ScoreEvent.PointTo(Side.HOME)))
        repo.finish("nope")
        repo.logs.value.shouldBeEmpty()
    }

    @Test
    fun matches_are_newest_first() {
        val settings = MapSettings()
        val first = repo(settings).newMatch()
        val second = repo(settings, now = { t0 + 1.hours }).newMatch()
        repo(settings).logs.value.map { it.id } shouldBe listOf(second.id, first.id)
    }

    @Test
    fun a_match_survives_the_app_being_killed_mid_game() {
        // The whole reason this store is local-first: the phone is in a bag between
        // games and the process does not survive it.
        val settings = MapSettings()
        val created = repo(settings).newMatch()
        repo(settings).replaceEvents(
            created.id, listOf(ScoreEvent.PointTo(Side.HOME), ScoreEvent.PointTo(Side.AWAY))
        )
        repo(settings).get(created.id)?.state()?.currentGame shouldBe SideScore(home = 1, away = 1)
    }

    @Test
    fun another_accounts_matches_are_never_handed_back() {
        // Settings has no per-user namespacing and nothing guarantees a sign-out
        // hook runs before the next launch - a session can simply expire. So the
        // owner travels inside the payload, and a mismatch shows nothing.
        val settings = MapSettings()
        repo(settings, owner = "owner-1").newMatch()
        repo(settings, owner = "owner-2").logs.value.shouldBeEmpty()
    }

    @Test
    fun a_cache_with_no_owner_is_treated_as_a_mismatch() {
        // Signed out, or a payload written by a build before this scoping existed.
        // Same answer either way: an empty list beats leaking match names.
        val settings = MapSettings()
        repo(settings, owner = "owner-1").newMatch()
        repo(settings, owner = null).logs.value.shouldBeEmpty()
    }

    @Test
    fun an_unreadable_cache_yields_an_empty_list_rather_than_a_crash() {
        // Same contract as LocalVideoRepository.load: a payload this build cannot
        // decode must not take the whole match list down with it.
        val settings = MapSettings().apply { putString("score_logs_cache", "{ not json") }
        repo(settings).logs.value.shouldBeEmpty()
    }

    @Test
    fun removing_a_match_locally_takes_it_out_of_the_list() {
        val repo = repo()
        val created = repo.newMatch()
        repo.removeLocally(created.id)
        repo.logs.value.shouldBeEmpty()
        repo.get(created.id).shouldBeNull()
    }
}

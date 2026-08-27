package com.badmintontracker.shared.scoring

import com.badmintontracker.shared.testing.TestSupabase
import com.badmintontracker.shared.testing.jsonResponse
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant

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

    /**
     * A fake server that keeps whatever was last upserted and hands it back on the
     * next read. Echoing rather than returning a canned list is what makes the
     * push-then-pull round trip in [sync] testable at all: a mock that always
     * replies with an empty array would report every pushed row as unacknowledged.
     */
    private class FakeScoreLogsServer {
        var rows: String = "[]"
        var failWith: HttpStatusCode? = null
        val requests = mutableListOf<Pair<String, String>>()

        fun client() = TestSupabase.client { request ->
            val body = (request.body as? TextContent)?.text ?: ""
            requests += request.method.value to body
            val failure = failWith
            when {
                failure != null -> jsonResponse("""{"message":"nope"}""", status = failure)
                request.method == HttpMethod.Get -> jsonResponse(rows)
                request.method == HttpMethod.Post -> { rows = body; jsonResponse("[]") }
                else -> jsonResponse("[]")
            }
        }

        fun posts() = requests.filter { it.first == "POST" }
    }

    private fun syncingRepo(server: FakeScoreLogsServer, settings: MapSettings = MapSettings()) =
        ScoreLogsRepository(server.client(), settings, now = { t0 }, ownerId = { "owner-1" })

    @Test
    fun syncing_pushes_local_matches_and_then_stops_pushing_them() = runTest {
        val server = FakeScoreLogsServer()
        val repo = syncingRepo(server)
        val created = repo.newMatch()

        repo.sync().isSuccess shouldBe true

        server.posts().shouldHaveSize(1)
        server.posts().first().second.shouldContain(created.id)
        server.posts().first().second.shouldContain(""""title":"Thu League"""")

        // Nothing is dirty any more, so a second sync sends no rows at all. Without
        // this the app re-uploads every match it has ever scored on every refresh.
        repo.sync().isSuccess shouldBe true
        server.posts().shouldHaveSize(1)
    }

    @Test
    fun syncing_pulls_matches_this_device_has_never_seen() = runTest {
        // A match scored before a reinstall, or on the coach's other phone.
        val server = FakeScoreLogsServer()
        server.rows = """
            [{"id":"remote-1","video_id":null,"title":"Club night",
              "home_players":["Coen"],"away_players":["Marco"],
              "rules":{"points_to_win":21,"win_by":2,"cap":30,"interval_at":11,"games_to_win":2,"change_ends_at":11},
              "setup":{"doubles":false,"first_server":"home"},
              "events":[{"type":"point","side":"home"}],
              "status":"unbound",
              "created_at":"2026-08-26T18:00:00Z","updated_at":"2026-08-26T18:00:00Z"}]
        """.trimIndent()
        val repo = syncingRepo(server)

        repo.sync().isSuccess shouldBe true

        repo.logs.value.map { it.id } shouldBe listOf("remote-1")
        repo.get("remote-1")?.state()?.currentGame shouldBe SideScore(home = 1, away = 0)
    }

    @Test
    fun a_point_scored_while_the_sync_was_in_flight_is_not_lost() = runTest {
        // The sharpest edge in this class. The push goes out, and between it and the
        // pull landing the coach scores. The pulled row is the pre-point version we
        // ourselves just sent, and taking it would silently un-score a rally.
        // One repository, not two: the race is inside a single instance, and a
        // second one over the same Settings would simply be reading a stale copy.
        // lateinit because the handler has to reach the repository that owns it.
        var rows = "[]"
        var scoredMidFlight = false
        lateinit var repo: ScoreLogsRepository
        lateinit var createdId: String

        val client = TestSupabase.client { request ->
            val body = (request.body as? TextContent)?.text ?: ""
            when (request.method) {
                HttpMethod.Get -> {
                    if (!scoredMidFlight) {
                        scoredMidFlight = true
                        repo.replaceEvents(createdId, listOf(ScoreEvent.PointTo(Side.HOME)))
                    }
                    jsonResponse(rows)
                }
                else -> { rows = body; jsonResponse("[]") }
            }
        }
        repo = ScoreLogsRepository(client, MapSettings(), now = { t0 }, ownerId = { "owner-1" })
        createdId = repo.newMatch().id

        repo.sync().isSuccess shouldBe true

        repo.get(createdId)?.state()?.currentGame shouldBe SideScore(home = 1, away = 0)
    }

    @Test
    fun a_failed_sync_drops_nothing_and_retries_next_time() = runTest {
        // No signal is the normal case in a sports hall, not the exception.
        val server = FakeScoreLogsServer()
        val repo = syncingRepo(server)
        val created = repo.newMatch()
        server.failWith = HttpStatusCode.InternalServerError

        repo.sync().isFailure shouldBe true
        repo.logs.value.map { it.id } shouldBe listOf(created.id)

        server.failWith = null
        server.requests.clear()
        repo.sync().isSuccess shouldBe true
        // Still dirty after the failure, so the retry actually carries the match.
        server.posts().first().second.shouldContain(created.id)
    }

    @Test
    fun deleting_removes_a_match_locally_even_when_the_server_call_fails() = runTest {
        // Deliberate. The row is the user's own and the gesture is explicit, so a
        // match that reappears after a failed delete is a worse outcome than a
        // server row the next sync will not resurrect: removeLocally drops it from
        // the cache, and the pull only ever adds rows the server still returns.
        val server = FakeScoreLogsServer()
        val repo = syncingRepo(server)
        val created = repo.newMatch()
        server.failWith = HttpStatusCode.InternalServerError

        repo.delete(created.id).isFailure shouldBe true
        repo.logs.value.shouldBeEmpty()
        repo.get(created.id).shouldBeNull()
    }
}

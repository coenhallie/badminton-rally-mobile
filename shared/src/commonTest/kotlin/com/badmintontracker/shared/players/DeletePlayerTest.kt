package com.badmintontracker.shared.players

import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.prefs.RacketArm
import com.badmintontracker.shared.scoring.MatchSetup
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.badmintontracker.shared.scoring.ScoreLogStatus
import com.badmintontracker.shared.testing.TestSupabase
import com.badmintontracker.shared.testing.jsonResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.badmintontracker.shared.scoring.ScoringRules
import com.badmintontracker.shared.scoring.Side
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test

class DeletePlayerTest {

    private val t0 = Instant.parse("2026-10-31T12:00:00Z")

    private val marco = Player("player-marco", "owner-1", "Marco", RacketArm.RIGHT, t0, t0)
    private val ana = Player("player-ana", "owner-1", "Ana", null, t0, t0)

    private val scoreLogs = ScoreLogsRepository(
        client = null, settings = MapSettings(), now = { t0 }, ownerId = { "owner-1" },
    )
    private val localVideos = LocalVideoRepository(MapSettings())

    /**
     * [delete] and [refresh] are what matter here. A delete fails with
     * [deleteError] when that is set; a refresh runs [duringRefresh], then fails
     * with [refreshError] or publishes [refreshed] (the list unchanged when null).
     */
    private class StubPlayers(initial: List<Player>, var deleteError: Throwable? = null) : PlayersRepository {
        private val state = MutableStateFlow(initial)
        override val players: StateFlow<List<Player>> = state
        val deleted = mutableListOf<String>()
        var refreshed: List<Player>? = null
        var refreshError: Throwable? = null
        var duringRefresh: () -> Unit = {}

        override suspend fun refresh(): Result<Unit> {
            duringRefresh()
            refreshError?.let { return Result.failure(it) }
            refreshed?.let { state.value = it }
            return Result.success(Unit)
        }
        override suspend fun create(name: String, racketArm: RacketArm?): Result<Player> = error("unused")
        override suspend fun rename(id: String, name: String): Result<Unit> = error("unused")
        override suspend fun setRacketArm(id: String, racketArm: RacketArm?): Result<Unit> = error("unused")
        override suspend fun delete(id: String): Result<Unit> {
            deleteError?.let { return Result.failure(it) }
            deleted += id
            state.value = state.value.filterNot { it.id == id }
            return Result.success(Unit)
        }
    }

    private fun newLog(assignment: MatchPlayers): ScoreLog {
        val log = scoreLogs.create("Thu League", ScoringRules.BWF_21, MatchSetup(firstServer = Side.HOME))
        scoreLogs.assignPlayers(log.id, assignment)
        return scoreLogs.get(log.id)!!
    }

    private fun entry(id: String, assignment: MatchPlayers) = LocalVideoEntry(
        id = id, uri = "content://media/video/$id", displayName = "match.mp4",
        durationMs = 60_000L, sizeBytes = 1_000L, addedAtEpochMs = 0L,
        homePlayerId = assignment.homePlayerId, awayPlayerId = assignment.awayPlayerId,
        homePlayers = assignment.homePlayers, awayPlayers = assignment.awayPlayers,
    )

    private val onMarcoHome = MatchPlayers("player-marco", null, emptyList(), listOf("Li"))
    private val onMarcoAway = MatchPlayers(null, "player-marco", listOf("Coen"), emptyList())
    private val onAna = MatchPlayers("player-ana", null, emptyList(), emptyList())

    @Test
    fun deleting_a_player_rewrites_cached_logs_and_entries() = runTest {
        val players = StubPlayers(listOf(ana, marco))
        val home = newLog(onMarcoHome)
        val away = newLog(onMarcoAway)
        val other = newLog(onAna)
        localVideos.add(entry("e1", onMarcoHome))
        localVideos.add(entry("e2", onAna))

        val message = deletePlayerOrMessage(players, scoreLogs, localVideos, marco)

        message.shouldBeNull()
        players.deleted shouldBe listOf("player-marco")
        scoreLogs.get(home.id)?.matchPlayers shouldBe MatchPlayers(null, null, listOf("Marco"), listOf("Li"))
        scoreLogs.get(away.id)?.matchPlayers shouldBe MatchPlayers(null, null, listOf("Coen"), listOf("Marco"))
        scoreLogs.get(other.id)?.matchPlayers shouldBe onAna
        localVideos.get("e1")?.matchPlayers shouldBe MatchPlayers(null, null, listOf("Marco"), listOf("Li"))
        localVideos.get("e2")?.matchPlayers shouldBe onAna
    }

    @Test
    fun a_failed_delete_rewrites_nothing() = runTest {
        val players = StubPlayers(listOf(ana, marco), deleteError = IllegalStateException("HTTP 500 boom"))
        val home = newLog(onMarcoHome)
        localVideos.add(entry("e1", onMarcoHome))

        val message = deletePlayerOrMessage(players, scoreLogs, localVideos, marco)

        // A fixed sentence, never the raw HTTP text.
        message shouldBe PlayersCopy.DELETE_FAILED
        scoreLogs.get(home.id)?.matchPlayers shouldBe onMarcoHome
        localVideos.get("e1")?.matchPlayers shouldBe onMarcoHome
    }

    @Test
    fun a_player_missing_after_refresh_is_forgotten_in_cached_logs_and_entries() {
        val gone = newLog(onMarcoHome)
        val kept = newLog(onAna)
        localVideos.add(entry("e1", onMarcoAway))
        localVideos.add(entry("e2", onAna))

        forgetVanishedPlayers(listOf(ana, marco), emptySet(), listOf(ana), scoreLogs, localVideos)

        scoreLogs.get(gone.id)?.matchPlayers shouldBe MatchPlayers(null, null, listOf("Marco"), listOf("Li"))
        scoreLogs.get(kept.id)?.matchPlayers shouldBe onAna
        localVideos.get("e1")?.matchPlayers shouldBe MatchPlayers(null, null, listOf("Coen"), listOf("Marco"))
        localVideos.get("e2")?.matchPlayers shouldBe onAna
    }

    @Test
    fun a_refresh_that_loses_nobody_rewrites_nothing() {
        val log = newLog(onMarcoHome)

        // A player added elsewhere (in after, not before) is not a vanished one.
        forgetVanishedPlayers(listOf(marco), setOf("player-marco"), listOf(marco, ana), scoreLogs, localVideos)

        scoreLogs.get(log.id)?.matchPlayers shouldBe onMarcoHome
    }

    // refreshPlayersAndForgetVanishedOrMessage

    private var owner: String? = "owner-1"

    private suspend fun refresh(players: StubPlayers) =
        refreshPlayersAndForgetVanishedOrMessage(players, scoreLogs, localVideos) { owner }

    @Test
    fun a_refresh_forgets_a_player_deleted_elsewhere() = runTest {
        val players = StubPlayers(listOf(ana, marco)).apply { refreshed = listOf(ana) }
        val log = newLog(onMarcoHome)
        localVideos.add(entry("e1", onMarcoAway))

        refresh(players).shouldBeNull()

        scoreLogs.get(log.id)?.matchPlayers shouldBe MatchPlayers(null, null, listOf("Marco"), listOf("Li"))
        localVideos.get("e1")?.matchPlayers shouldBe MatchPlayers(null, null, listOf("Coen"), listOf("Marco"))
    }

    @Test
    fun an_empty_list_after_a_refresh_forgets_everyone() = runTest {
        val players = StubPlayers(listOf(ana, marco)).apply { refreshed = emptyList() }
        val home = newLog(onMarcoHome)
        val other = newLog(onAna)

        refresh(players).shouldBeNull()

        scoreLogs.get(home.id)?.matchPlayers shouldBe MatchPlayers(null, null, listOf("Marco"), listOf("Li"))
        scoreLogs.get(other.id)?.matchPlayers shouldBe MatchPlayers(null, null, listOf("Ana"), emptyList())
    }

    @Test
    fun a_failed_refresh_forgets_nobody() = runTest {
        val players = StubPlayers(listOf(marco)).apply {
            refreshed = emptyList()
            refreshError = IllegalStateException("HTTP 503")
        }
        val log = newLog(onMarcoHome)

        refresh(players) shouldBe PlayersCopy.LOAD_FAILED

        scoreLogs.get(log.id)?.matchPlayers shouldBe onMarcoHome
    }

    @Test
    fun a_refresh_with_no_session_forgets_nobody() = runTest {
        owner = null
        val players = StubPlayers(listOf(marco)).apply { refreshed = emptyList() }
        val log = newLog(onMarcoHome)

        refresh(players).shouldBeNull()

        scoreLogs.get(log.id)?.matchPlayers shouldBe onMarcoHome
    }

    @Test
    fun a_refresh_across_a_change_of_owner_forgets_nobody() = runTest {
        val players = StubPlayers(listOf(marco)).apply {
            refreshed = emptyList()
            duringRefresh = { owner = "owner-2" }
        }
        val log = newLog(onMarcoHome)

        refresh(players).shouldBeNull()

        scoreLogs.get(log.id)?.matchPlayers shouldBe onMarcoHome
    }

    @Test
    fun a_previous_coachs_player_still_named_here_is_forgotten() = runTest {
        // Signed out and in as someone else, and a match on this phone still names
        // the last coach's player. The database refuses that id for this coach (the
        // same-coach trigger), so it goes like a deleted one. The name comes from
        // whatever list this phone held before the refresh.
        val theirs = marco.copy(ownerId = "owner-0")
        val players = StubPlayers(listOf(theirs, ana)).apply { refreshed = listOf(ana) }
        val log = newLog(onMarcoHome)
        localVideos.add(entry("e1", onMarcoAway))

        refresh(players).shouldBeNull()

        scoreLogs.get(log.id)?.matchPlayers shouldBe MatchPlayers(null, null, listOf("Marco"), listOf("Li"))
        localVideos.get("e1")?.matchPlayers shouldBe MatchPlayers(null, null, listOf("Coen"), listOf("Marco"))
    }

    @Test
    fun a_named_player_the_list_never_held_is_forgotten_once_a_refresh_lacks_them() = runTest {
        // The cold-start race: the list before the refresh is still empty (the cache
        // has not been re-read), yet the matches here name players. Whoever the
        // refreshed list lacks is dead or not this coach's; whoever it has stays.
        val players = StubPlayers(emptyList()).apply { refreshed = listOf(ana) }
        val gone = newLog(onMarcoHome)
        val kept = newLog(onAna)
        localVideos.add(entry("e1", onMarcoAway))
        localVideos.add(entry("e2", onAna))

        refresh(players).shouldBeNull()

        // No name to keep: this phone's list never held Marco's, so the side is left empty.
        scoreLogs.get(gone.id)?.matchPlayers shouldBe MatchPlayers(null, null, emptyList(), listOf("Li"))
        scoreLogs.get(kept.id)?.matchPlayers shouldBe onAna
        localVideos.get("e1")?.matchPlayers shouldBe MatchPlayers(null, null, listOf("Coen"), emptyList())
        localVideos.get("e2")?.matchPlayers shouldBe onAna
    }

    @Test
    fun a_player_named_while_the_refresh_is_out_is_never_forgotten() = runTest {
        // The refreshed list is as old as its select. A player created after that
        // began (an inline Add player, or a log the list's sync pulls meanwhile) is
        // missing from it and still alive: clearing the id here would unassign that
        // player on the server at the next push.
        val newcomer = MatchPlayers("player-new", null, emptyList(), listOf("Li"))
        val players = StubPlayers(listOf(ana)).apply { refreshed = listOf(ana) }
        val log = newLog(onAna)
        players.duringRefresh = {
            scoreLogs.assignPlayers(log.id, newcomer)
            localVideos.add(entry("e1", newcomer))
        }

        refresh(players).shouldBeNull()

        scoreLogs.get(log.id)?.matchPlayers shouldBe newcomer
        localVideos.get("e1")?.matchPlayers shouldBe newcomer
    }

    /**
     * score_logs as far as its player foreign key goes: an upsert naming an id
     * outside [livePlayers] is refused whole, as Postgres refuses the batch, and a
     * read hands back what was accepted.
     */
    private class ScoreLogsServer(var livePlayers: Set<String>) {
        val rows = linkedMapOf<String, JsonObject>()

        fun seed(log: ScoreLog) {
            rows[log.id] = wire.encodeToJsonElement(ScoreLog.serializer(), log).jsonObject
        }

        fun client() = TestSupabase.client { request ->
            when (request.method) {
                HttpMethod.Get -> jsonResponse(JsonArray(rows.values.toList()).toString())
                HttpMethod.Post -> {
                    val pushed = Json.parseToJsonElement((request.body as TextContent).text)
                        .jsonArray.map { it.jsonObject }
                    val dead = pushed
                        .flatMap { listOf(it["home_player_id"], it["away_player_id"]) }
                        .mapNotNull { it?.jsonPrimitive?.contentOrNull }
                        .filterNot { it in livePlayers }
                    if (dead.isNotEmpty()) {
                        jsonResponse(
                            """{"code":"23503","message":"insert or update on table \"score_logs\" violates foreign key constraint"}""",
                            HttpStatusCode.Conflict,
                        )
                    } else {
                        pushed.forEach { rows[it.getValue("id").jsonPrimitive.content] = it }
                        jsonResponse("[]")
                    }
                }
                else -> jsonResponse("[]")
            }
        }

        private companion object {
            val wire = Json { encodeDefaults = true }
        }
    }

    @Test
    fun a_log_naming_a_player_this_phone_never_cached_stops_blocking_every_sync() = runTest {
        // Path 1 of the final review, end to end. The coach's other phone assigned a
        // match to Li, a player this phone has never cached.
        val server = ScoreLogsServer(livePlayers = setOf("player-li"))
        server.seed(
            ScoreLog(
                id = "log-li", videoId = null, title = "Thu League",
                homePlayerId = "player-li", awayPlayers = listOf("Jo"),
                rules = ScoringRules.BWF_21, setup = MatchSetup(firstServer = Side.HOME),
                events = emptyList(), status = ScoreLogStatus.LIVE, createdAt = t0, updatedAt = t0,
            ),
        )
        val logs = ScoreLogsRepository(server.client(), MapSettings(), now = { t0 }, ownerId = { owner })
        val players = StubPlayers(listOf(ana)).apply { refreshError = IllegalStateException("HTTP 503") }

        // One pull: the players refresh fails softly, the score-log sync works.
        refreshPlayersAndForgetVanishedOrMessage(players, logs, localVideos) { owner } shouldBe
            PlayersCopy.LOAD_FAILED
        logs.sync().isSuccess shouldBe true
        // Edited here (a new title), so it is dirty; and a match scored here.
        logs.updateDetails("log-li", "Thu League final", null, logs.get("log-li")!!.matchPlayers)
        val scoredHere = logs.create("Fri ladder", ScoringRules.BWF_21, MatchSetup(firstServer = Side.HOME))

        // Li is deleted on the other phone: the database refuses the id from now on,
        // and with it the whole batch, the match scored here included.
        server.livePlayers = emptySet()
        logs.sync().isFailure shouldBe true
        server.rows.keys shouldBe setOf("log-li")

        // The next pull's players refresh works, and its list has no Li.
        players.refreshError = null
        players.refreshed = listOf(ana)
        refreshPlayersAndForgetVanishedOrMessage(players, logs, localVideos) { owner }.shouldBeNull()

        logs.sync().isSuccess shouldBe true
        val pushed = server.rows.getValue("log-li")
        pushed["title"]?.jsonPrimitive?.content shouldBe "Thu League final"
        pushed["home_player_id"] shouldBe JsonNull
        // This phone never knew Li's name, so the side is left empty, not "".
        pushed["home_players"] shouldBe JsonArray(emptyList())
        pushed["away_players"]?.jsonArray?.map { it.jsonPrimitive.content } shouldBe listOf("Jo")
        server.rows.keys shouldBe setOf("log-li", scoredHere.id)
    }
}

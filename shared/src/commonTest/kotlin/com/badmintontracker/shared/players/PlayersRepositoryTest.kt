package com.badmintontracker.shared.players

import com.badmintontracker.shared.prefs.RacketArm
import com.badmintontracker.shared.testing.TestSupabase
import com.badmintontracker.shared.testing.jsonResponse
import com.russhwolf.settings.MapSettings
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.http.content.TextContent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class PlayersRepositoryTest {

    private val cacheKey = "players_cache"

    // Deliberately out of name order, to prove the repository sorts.
    private val seeded = """
      [
        {"id":"p1","owner_id":"u1","name":"zoe","racket_arm":"left","created_at":"2026-10-31T12:00:00Z","updated_at":"2026-10-31T12:00:00Z"},
        {"id":"p2","owner_id":"u1","name":"Bea","racket_arm":null,"created_at":"2026-10-31T12:00:01Z","updated_at":"2026-10-31T12:00:01Z"},
        {"id":"p3","owner_id":"u1","name":"alex","racket_arm":"right","created_at":"2026-10-31T12:00:02Z","updated_at":"2026-10-31T12:00:02Z"}
      ]
    """.trimIndent()

    private fun tokenResponseFor(userId: String) = """
        {
          "access_token":"jwt-$userId","token_type":"bearer","expires_in":3600,
          "refresh_token":"refresh-$userId",
          "user":{"id":"$userId","aud":"authenticated","role":"authenticated",
                  "email":"$userId@example.com","created_at":"2026-10-31T12:00:00Z",
                  "updated_at":"2026-10-31T12:00:00Z"}
        }
    """.trimIndent()

    private suspend fun SupabaseClient.signInAs(userId: String) {
        auth.signInWith(Email) {
            email = "$userId@example.com"
            password = "password"
        }
    }

    /**
     * A client signed in as "u1", who owns [seeded]: the list only ever shows the
     * signed-in coach's players. [handler] answers everything but the sign-in.
     */
    private suspend fun signedInAsU1(
        settings: MapSettings = MapSettings(),
        handler: MockRequestHandler,
    ): SupabaseClient {
        val client = TestSupabase.client(settings) { request ->
            if (request.url.encodedPath.contains("/token")) jsonResponse(tokenResponseFor("u1"))
            else handler(request)
        }
        client.signInAs("u1")
        return client
    }

    @Test
    fun refresh_publishes_players_sorted_by_name() = runTest {
        var capturedUrl: String? = null
        val client = signedInAsU1 { request ->
            capturedUrl = request.url.toString()
            jsonResponse(seeded)
        }
        val repo = PlayersRepositoryImpl(client, MapSettings())

        repo.refresh().isSuccess shouldBe true

        repo.players.value.map { it.name } shouldBe listOf("alex", "Bea", "zoe")
        repo.players.value.first { it.id == "p1" }.racketArm shouldBe RacketArm.LEFT
        repo.players.value.first { it.id == "p2" }.racketArm shouldBe null
        capturedUrl!!.shouldContain("players")
        capturedUrl.shouldContain("order=created_at.asc")
    }

    @Test
    fun the_cache_survives_a_cold_start_offline() = runTest {
        val settings = MapSettings()
        val client = TestSupabase.client { request ->
            if (request.url.encodedPath.contains("/token")) jsonResponse(tokenResponseFor("u1"))
            else jsonResponse(seeded)
        }
        client.signInAs("u1")
        PlayersRepositoryImpl(client, settings).refresh()

        val offline = TestSupabase.client { request ->
            if (request.url.encodedPath.contains("/token")) jsonResponse(tokenResponseFor("u1"))
            else error("offline")
        }
        offline.signInAs("u1")

        PlayersRepositoryImpl(offline, settings).players.value.map { it.name } shouldBe
            listOf("alex", "Bea", "zoe")
    }

    @Test
    fun another_owner_never_sees_the_cache() = runTest {
        val settings = MapSettings()
        val clientA = TestSupabase.client { request ->
            if (request.url.encodedPath.contains("/token")) jsonResponse(tokenResponseFor("user-a"))
            else jsonResponse(seeded)
        }
        clientA.signInAs("user-a")
        PlayersRepositoryImpl(clientA, settings).refresh()
        settings.getStringOrNull(cacheKey)!!.shouldContain("user-a")

        val clientB = TestSupabase.client { request ->
            if (request.url.encodedPath.contains("/token")) jsonResponse(tokenResponseFor("user-b"))
            else error("the cache read must not need the network")
        }
        clientB.signInAs("user-b")

        PlayersRepositoryImpl(clientB, settings).players.value.shouldBeEmpty()
    }

    @Test
    fun create_refuses_a_blank_name_without_a_request() = runTest {
        var requests = 0
        val client = TestSupabase.client { _ ->
            requests += 1
            jsonResponse(seeded)
        }
        val repo = PlayersRepositoryImpl(client, MapSettings())

        val result = repo.create("   ", null)

        result.isFailure shouldBe true
        result.exceptionOrNull()?.message shouldBe "Enter a name."
        requests shouldBe 0
    }

    @Test
    fun create_sends_the_trimmed_name_and_hand() = runTest {
        var posted: String? = null
        val client = signedInAsU1 { request ->
            val body = (request.body as? TextContent)?.text
            if (body == null) jsonResponse(seeded)
            else {
                posted = body
                jsonResponse(
                    """[{"id":"p4","owner_id":"u1","name":"Marco Vidal","racket_arm":"right","created_at":"2026-10-31T12:00:03Z","updated_at":"2026-10-31T12:00:03Z"}]""",
                    HttpStatusCode.Created,
                )
            }
        }
        val repo = PlayersRepositoryImpl(client, MapSettings())
        repo.refresh()

        val created = repo.create("  Marco Vidal  ", RacketArm.RIGHT)

        created.getOrNull()?.id shouldBe "p4"
        posted!!.shouldContain(""""name":"Marco Vidal"""")
        posted.shouldContain(""""racket_arm":"right"""")
        repo.players.value.map { it.name } shouldBe listOf("alex", "Bea", "Marco Vidal", "zoe")
    }

    @Test
    fun clearing_the_hand_sends_null() = runTest {
        var patched: String? = null
        val client = signedInAsU1 { request ->
            val body = (request.body as? TextContent)?.text
            if (body == null) jsonResponse(seeded)
            else {
                patched = body
                jsonResponse("[]")
            }
        }
        val repo = PlayersRepositoryImpl(client, MapSettings())
        repo.refresh()

        repo.setRacketArm("p1", null).isSuccess shouldBe true

        patched!!.shouldContain(""""racket_arm":null""")
        repo.players.value.first { it.id == "p1" }.racketArm shouldBe null
    }

    @Test
    fun rename_sends_the_trimmed_name_and_resorts() = runTest {
        var patched: String? = null
        val client = signedInAsU1 { request ->
            val body = (request.body as? TextContent)?.text
            if (body == null) jsonResponse(seeded)
            else {
                patched = body
                jsonResponse("[]")
            }
        }
        val repo = PlayersRepositoryImpl(client, MapSettings())
        repo.refresh()

        repo.rename("p1", "  Aaron  ").isSuccess shouldBe true

        patched!!.shouldContain(""""name":"Aaron"""")
        repo.players.value.map { it.name } shouldBe listOf("Aaron", "alex", "Bea")
    }

    @Test
    fun delete_drops_the_player_from_the_list() = runTest {
        val client = signedInAsU1 { jsonResponse(seeded) }
        val repo = PlayersRepositoryImpl(client, MapSettings())
        repo.refresh()

        repo.delete("p2").isSuccess shouldBe true

        repo.players.value.map { it.id } shouldBe listOf("p3", "p1")
    }

    @Test
    fun cache_is_republished_once_the_owner_becomes_known_after_construction() = runTest {
        val settings = MapSettings()
        val warm = TestSupabase.client(settings) { request ->
            if (request.url.encodedPath.contains("/token")) jsonResponse(tokenResponseFor("u1"))
            else jsonResponse(seeded)
        }
        warm.signInAs("u1")
        PlayersRepositoryImpl(warm, settings).refresh() // persists the cache under "u1"

        // A cold client over settings holding ONLY the cache envelope, not a
        // persisted session, so nothing races supabase-kt's real asynchronous
        // session restore (see AnnotationLabelsRepositoryTest for the long form).
        val coldSettings = MapSettings().apply { putString(cacheKey, settings.getString(cacheKey, "")) }
        val cold = TestSupabase.client(coldSettings) { request ->
            if (request.url.encodedPath.contains("/token")) jsonResponse(tokenResponseFor("u1"))
            else error("the cold-start reconciliation must read the local cache, not the network")
        }
        cold.auth.currentUserOrNull() shouldBe null

        // The gate stands in for awaitInitialization(), so "sign-in observed"
        // strictly happens before "reconciliation re-reads the cache".
        val gate = CompletableDeferred<Unit>()
        val repo = PlayersRepositoryImpl(cold, coldSettings) { gate.await() }
        repo.players.value.shouldBeEmpty() // fails safe: owner not known yet

        cold.signInAs("u1")
        gate.complete(Unit)
        repo.cacheReconciliation.join()

        repo.players.value.map { it.name } shouldBe listOf("alex", "Bea", "zoe")
    }

    @Test
    fun the_cold_start_reconciliation_never_serves_one_owners_cache_to_another() = runTest {
        val settings = MapSettings()
        val client = TestSupabase.client(settings) { request ->
            when {
                request.url.encodedPath.contains("/token") &&
                    (request.body as? TextContent)?.text?.contains("user-a") == true ->
                    jsonResponse(tokenResponseFor("user-a"))
                request.url.encodedPath.contains("/token") -> jsonResponse(tokenResponseFor("user-b"))
                else -> jsonResponse(seeded)
            }
        }
        client.signInAs("user-a")
        PlayersRepositoryImpl(client, settings).refresh() // cache stamped "user-a"

        // A cold client over user-a's cache whose owner turns out to be user-b.
        val cold = TestSupabase.client(settings) { request ->
            if (request.url.encodedPath.contains("/token")) jsonResponse(tokenResponseFor("user-b"))
            else error("must not need the network")
        }
        cold.signInAs("user-b")

        val repo = PlayersRepositoryImpl(cold, settings)
        repo.cacheReconciliation.join()

        repo.players.value.shouldBeEmpty()
    }

    @Test
    fun a_list_longer_than_one_response_arrives_whole() = runTest {
        // A player missing from the refreshed list is taken as deleted and
        // forgotten in every match that names them, so a list cut at PostgREST's
        // max_rows would unassign live players.
        val total = 1_050
        val client = signedInAsU1 { request ->
            val offset = request.url.parameters["offset"]?.toInt() ?: 0
            val limit = minOf(request.url.parameters["limit"]?.toInt() ?: 1_000, 1_000)
            val end = minOf(offset + limit, total)
            val page = (offset until end).joinToString(",") { i ->
                """{"id":"p$i","owner_id":"u1","name":"P$i","racket_arm":null,"created_at":"2026-10-31T12:00:00Z","updated_at":"2026-10-31T12:00:00Z"}"""
            }
            respond(
                content = ByteReadChannel("[$page]"),
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                    HttpHeaders.ContentRange to listOf("$offset-${end - 1}/$total"),
                ),
            )
        }
        val repo = PlayersRepositoryImpl(client, MapSettings())

        repo.refresh().isSuccess shouldBe true

        repo.players.value.map { it.id }.toSet().size shouldBe total
    }

    @Test
    fun pages_are_cut_from_a_total_order() = runTest {
        var query: String? = null
        val client = signedInAsU1 { request ->
            query = request.url.toString()
            jsonResponse("[]")
        }
        PlayersRepositoryImpl(client, MapSettings()).refresh()
        query!!.shouldContain("order=created_at.asc.nullslast%2Cid.asc.nullslast")
    }

    // The list in memory follows the signed-in coach.

    @Test
    fun a_refresh_answered_for_another_coach_shows_nothing() = runTest {
        // A response that raced a change of account: the rows are u1's, the
        // session is user-b's.
        val client = TestSupabase.client { request ->
            if (request.url.encodedPath.contains("/token")) jsonResponse(tokenResponseFor("user-b"))
            else jsonResponse(seeded)
        }
        client.signInAs("user-b")
        val settings = MapSettings()
        val repo = PlayersRepositoryImpl(client, settings)

        repo.refresh().isSuccess shouldBe true

        repo.players.value.shouldBeEmpty()
    }

    @Test
    fun signing_out_takes_the_players_off_the_list() = runTest {
        val client = signedInAsU1 { jsonResponse(seeded) }
        val repo = PlayersRepositoryImpl(client, MapSettings())
        repo.refresh()
        repo.players.value.map { it.id }.toSet() shouldBe setOf("p1", "p2", "p3")

        client.auth.signOut()

        repo.players.first { it.isEmpty() }.shouldBeEmpty()
    }

    @Test
    fun the_next_coach_never_sees_or_picks_the_last_ones_players() = runTest {
        val settings = MapSettings()
        val client = TestSupabase.client(settings) { request ->
            when {
                request.url.encodedPath.contains("/token") &&
                    (request.body as? TextContent)?.text?.contains("user-b") == true ->
                    jsonResponse(tokenResponseFor("user-b"))
                request.url.encodedPath.contains("/token") -> jsonResponse(tokenResponseFor("u1"))
                else -> jsonResponse(seeded)
            }
        }
        client.signInAs("u1")
        val repo = PlayersRepositoryImpl(client, settings)
        repo.refresh()
        repo.players.value.map { it.id }.toSet() shouldBe setOf("p1", "p2", "p3")

        // Straight into another account, offline or before any refresh of theirs.
        client.signInAs("user-b")

        repo.players.first { it.isEmpty() }.shouldBeEmpty()
        // And u1's cache is still u1's, for when they are back.
        settings.getStringOrNull(cacheKey)!!.shouldContain("\"owner_id\":\"u1\"")
    }
}

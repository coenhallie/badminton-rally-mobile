package com.badmintontracker.shared.repo

import com.badmintontracker.shared.model.LabelColor
import com.badmintontracker.shared.model.LabelUsage
import com.badmintontracker.shared.testing.TestSupabase
import com.badmintontracker.shared.testing.jsonResponse
import com.russhwolf.settings.MapSettings
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class AnnotationLabelsRepositoryTest {

    // Mirrors AnnotationLabelsRepositoryImpl's private KEY_CACHE. Duplicated
    // here rather than exposed from the impl, since only one test needs it.
    private val cacheKey = "annotation_labels_cache"

    private val seeded = """
      [
        {"id":"l1","name":"Good shot","color_key":"green","created_at":"2026-08-24T12:00:00Z"},
        {"id":"l2","name":"Forced error","color_key":"amber","created_at":"2026-08-24T12:00:01Z"},
        {"id":"l3","name":"Unforced error","color_key":"red","created_at":"2026-08-24T12:00:02Z"}
      ]
    """.trimIndent()

    // A real /auth/v1/token grant response, the same shape AuthRepositoryTest uses.
    // Signing in for real (rather than hand-building a session) is the only
    // deterministic way found to make a TestSupabase client "report" a given user:
    // client.auth.currentUserOrNull() reads a StateFlow that a fresh client's own
    // storage-restore populates asynchronously (confirmed empirically - a second
    // client built over Settings a prior client already persisted a session into
    // still reports null immediately after construction, with no way to await it
    // from inside a synchronous constructor). Signing in via the mocked token
    // endpoint updates that StateFlow synchronously before the suspend call
    // returns, so it is what every owner-scoping test below uses.
    private fun tokenResponseFor(userId: String) = """
        {
          "access_token":"jwt-$userId","token_type":"bearer","expires_in":3600,
          "refresh_token":"refresh-$userId",
          "user":{"id":"$userId","aud":"authenticated","role":"authenticated",
                  "email":"$userId@example.com","created_at":"2026-08-24T12:00:00Z",
                  "updated_at":"2026-08-24T12:00:00Z"}
        }
    """.trimIndent()

    private suspend fun SupabaseClient.signInAs(userId: String) {
        auth.signInWith(Email) {
            email = "$userId@example.com"
            password = "password"
        }
    }

    @Test
    fun refresh_loads_labels_in_creation_order_and_publishes_them() = runTest {
        var capturedUrl: String? = null
        val client = TestSupabase.client { request ->
            capturedUrl = request.url.toString()
            jsonResponse(seeded)
        }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())

        repo.refresh().isSuccess shouldBe true

        repo.labels.value shouldHaveSize 3
        repo.labels.value[0].name shouldBe "Good shot"
        capturedUrl!!.shouldContain("annotation_labels")
        capturedUrl!!.shouldContain("order=created_at.asc")
    }

    @Test
    fun refresh_caches_the_list_so_a_cold_start_offline_still_has_a_picker() = runTest {
        val settings = MapSettings()
        val client = TestSupabase.client { request ->
            if (request.url.encodedPath.contains("/token")) jsonResponse(tokenResponseFor("u1"))
            else jsonResponse(seeded)
        }
        client.signInAs("u1")
        AnnotationLabelsRepositoryImpl(client, settings).refresh()

        // Same signed-in user, but the data endpoint is unreachable - the
        // scenario the cache exists for.
        val offline = TestSupabase.client { request ->
            if (request.url.encodedPath.contains("/token")) jsonResponse(tokenResponseFor("u1"))
            else error("offline")
        }
        offline.signInAs("u1")
        AnnotationLabelsRepositoryImpl(offline, settings).labels.value shouldHaveSize 3
    }

    @Test
    fun create_writes_the_cache_so_a_cold_start_offline_still_has_the_new_label() = runTest {
        val settings = MapSettings()
        val client = TestSupabase.client { request ->
            when {
                request.url.encodedPath.contains("/token") -> jsonResponse(tokenResponseFor("u1"))
                (request.body as? TextContent)?.text != null -> jsonResponse(
                    """[{"id":"l4","name":"Net kill","color_key":"teal","created_at":"2026-08-24T12:00:03Z"}]""",
                    HttpStatusCode.Created,
                )
                else -> jsonResponse(seeded)
            }
        }
        client.signInAs("u1")
        val repo = AnnotationLabelsRepositoryImpl(client, settings)
        repo.refresh()
        repo.create("Net kill", null, LabelUsage.BOTH).isSuccess shouldBe true

        val offline = TestSupabase.client { request ->
            if (request.url.encodedPath.contains("/token")) jsonResponse(tokenResponseFor("u1"))
            else error("offline")
        }
        offline.signInAs("u1")

        AnnotationLabelsRepositoryImpl(offline, settings).labels.value
            .any { it.name == "Net kill" } shouldBe true
    }

    @Test
    fun loadCache_returns_nothing_when_the_cached_owner_does_not_match_the_current_user() = runTest {
        val settings = MapSettings()
        val clientA = TestSupabase.client { request ->
            if (request.url.encodedPath.contains("/token")) jsonResponse(tokenResponseFor("user-a"))
            else jsonResponse(seeded)
        }
        clientA.signInAs("user-a")
        clientA.auth.currentUserOrNull()?.id shouldBe "user-a" // sanity: sign-in actually took
        AnnotationLabelsRepositoryImpl(clientA, settings).refresh()

        // A different user signs in on the same device. Their client must never
        // surface user-a's label names, even before their first refresh() lands.
        val clientB = TestSupabase.client { request ->
            if (request.url.encodedPath.contains("/token")) jsonResponse(tokenResponseFor("user-b"))
            else error("the cache read must not need the network")
        }
        clientB.signInAs("user-b")
        clientB.auth.currentUserOrNull()?.id shouldBe "user-b" // sanity: sign-in actually took

        AnnotationLabelsRepositoryImpl(clientB, settings).labels.value.shouldBeEmpty()
    }

    @Test
    fun create_with_no_colour_takes_the_first_unused_swatch() = runTest {
        var posted: String? = null
        val client = TestSupabase.client { request ->
            val body = (request.body as? TextContent)?.text
            if (body == null) jsonResponse(seeded)
            else {
                posted = body
                jsonResponse(
                    """[{"id":"l4","name":"Net kill","color_key":"teal","created_at":"2026-08-24T12:00:03Z"}]""",
                    HttpStatusCode.Created,
                )
            }
        }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())
        repo.refresh()

        val created = repo.create("Net kill", null, LabelUsage.BOTH)

        created.isSuccess shouldBe true
        // green, amber and red are taken by the seeded three, so teal is next.
        posted!!.shouldContain(""""color_key":"teal"""")
        posted!!.shouldContain(""""usage":"both"""")
        repo.labels.value shouldHaveSize 4
    }

    @Test
    fun create_rejects_a_blank_name() = runTest {
        val client = TestSupabase.client { jsonResponse(seeded) }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())

        repo.create("   ", null, LabelUsage.BOTH).isFailure shouldBe true
    }

    @Test
    fun create_trims_the_name_before_posting() = runTest {
        var posted: String? = null
        val client = TestSupabase.client { request ->
            val body = (request.body as? TextContent)?.text
            if (body == null) jsonResponse(seeded)
            else {
                posted = body
                jsonResponse(
                    """[{"id":"l4","name":"Net kill","color_key":"teal","created_at":"2026-08-24T12:00:03Z"}]""",
                    HttpStatusCode.Created,
                )
            }
        }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())

        repo.create("  Net kill  ", null, LabelUsage.BOTH).isSuccess shouldBe true

        posted!!.shouldContain(""""name":"Net kill"""")
    }

    @Test
    fun create_rejects_a_name_over_the_length_limit() = runTest {
        val client = TestSupabase.client { jsonResponse(seeded) }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())

        val overLimit = repo.create("a".repeat(25), null, LabelUsage.BOTH)
        overLimit.isFailure shouldBe true
        overLimit.exceptionOrNull()?.message shouldContain "up to 24 characters"
    }

    @Test
    fun create_rejects_a_name_already_in_use_regardless_of_case() = runTest {
        val client = TestSupabase.client { jsonResponse(seeded) }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())
        repo.refresh()

        repo.create("good SHOT", null, LabelUsage.BOTH).isFailure shouldBe true
    }

    // The shape Postgrest actually returns for a unique-violation: HTTP 409 with a
    // JSON body of {code, message}. supabase-kt decodes this into a
    // PostgrestRestException whose message is "<message>\n    Code:\n    <code>\n...",
    // so it carries both the postgres code 23505 and the constraint name.
    private val uniqueViolation = """
        {"code":"23505","message":"duplicate key value violates unique constraint \"annotation_labels_owner_name_key\""}
    """.trimIndent()

    @Test
    fun create_maps_a_server_side_duplicate_name_rejection_to_the_same_sentence() = runTest {
        // No refresh(): the in-memory list validate() checks is empty, so the
        // in-memory duplicate check genuinely passes and the request reaches the
        // server. This is the path a cold-start-offline or not-yet-refreshed user
        // hits, and it is asDuplicateName, not validate(), that must turn the
        // server's rejection into a readable sentence.
        val client = TestSupabase.client { _ ->
            respondError(
                status = HttpStatusCode.Conflict,
                content = uniqueViolation,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())

        val result = repo.create("Net kill", null, LabelUsage.BOTH)

        result.isFailure shouldBe true
        result.exceptionOrNull()?.message shouldBe "You already have a label called \"Net kill\"."
    }

    @Test
    fun rename_maps_a_server_side_duplicate_name_rejection_to_the_same_sentence() = runTest {
        val client = TestSupabase.client { _ ->
            respondError(
                status = HttpStatusCode.Conflict,
                content = uniqueViolation,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())

        val result = repo.rename("l1", "Good shot")

        result.isFailure shouldBe true
        result.exceptionOrNull()?.message shouldBe "You already have a label called \"Good shot\"."
    }

    @Test
    fun rename_updates_the_published_list() = runTest {
        val client = TestSupabase.client { jsonResponse(seeded) }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())
        repo.refresh()

        repo.rename("l1", "Great shot").isSuccess shouldBe true

        repo.labels.value.first { it.id == "l1" }.name shouldBe "Great shot"
    }

    @Test
    fun delete_drops_the_label_from_the_published_list() = runTest {
        val client = TestSupabase.client { jsonResponse(seeded) }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())
        repo.refresh()

        repo.delete("l2").isSuccess shouldBe true

        repo.labels.value shouldHaveSize 2
        repo.labels.value.none { it.id == "l2" } shouldBe true
    }

    @Test
    fun recolor_updates_the_published_list() = runTest {
        val client = TestSupabase.client { jsonResponse(seeded) }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())
        repo.refresh()

        repo.recolor("l1", LabelColor.SLATE).isSuccess shouldBe true

        repo.labels.value.first { it.id == "l1" }.colorKey shouldBe "slate"
    }

    @Test
    fun cache_is_republished_once_the_owner_becomes_known_after_construction() = runTest {
        val settings = MapSettings()
        val warm = TestSupabase.client(settings) { request ->
            if (request.url.encodedPath.contains("/token")) jsonResponse(tokenResponseFor("u1"))
            else jsonResponse(seeded)
        }
        warm.signInAs("u1")
        AnnotationLabelsRepositoryImpl(warm, settings).refresh() // persists the cache under "u1"

        // A cold client built over settings holding ONLY the cache envelope, not
        // a persisted session. With no session on disk, there is nothing for
        // supabase-kt to restore - so currentUserOrNull() being null and
        // labels.value starting empty are facts about this settings instance,
        // not a race against supabase-kt's real (and unmockable) asynchronous
        // session restore that a shared-settings cold start would be.
        val coldSettings = MapSettings().apply {
            putString(cacheKey, settings.getString(cacheKey, ""))
        }
        val cold = TestSupabase.client(coldSettings) { request ->
            if (request.url.encodedPath.contains("/token")) jsonResponse(tokenResponseFor("u1"))
            else error("the cold-start reconciliation must read the local cache, not the network")
        }
        cold.auth.currentUserOrNull() shouldBe null

        // A manually-controlled gate stands in for the real awaitInitialization():
        // the reconciliation job parks on it until this test explicitly releases
        // it, after signing in - so "sign-in observed" strictly happens-before
        // "reconciliation re-reads the cache," deterministically, with no
        // dependency on real scheduling. The tradeoff: this no longer exercises
        // awaitInitialization() itself - that call is covered by the default
        // argument on the public constructor, not by a test.
        val gate = CompletableDeferred<Unit>()
        val repo = AnnotationLabelsRepositoryImpl(cold, coldSettings) { gate.await() }
        repo.labels.value.shouldBeEmpty() // fails safe: no session, owner not known yet

        cold.signInAs("u1")
        gate.complete(Unit)
        repo.cacheReconciliation.join()

        repo.labels.value shouldHaveSize 3
        repo.labels.value[0].name shouldBe "Good shot"
    }

    @Test
    fun the_cold_start_reconciliation_never_serves_one_users_cache_to_another() = runTest {
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
        AnnotationLabelsRepositoryImpl(client, settings).refresh() // cache stamped "user-a"
        client.signInAs("user-b") // same client, different signed-in owner now

        // A cold client over settings holding user-a's cache, but which restores
        // (or is already signed in as) user-b - the reconciliation path must
        // never hand user-a's label names to user-b, exactly like the
        // constructor-time check above.
        val cold = TestSupabase.client(settings) { request ->
            if (request.url.encodedPath.contains("/token")) jsonResponse(tokenResponseFor("user-b"))
            else error("must not need the network")
        }
        cold.signInAs("user-b")

        val repo = AnnotationLabelsRepositoryImpl(cold, settings)
        repo.cacheReconciliation.join()

        repo.labels.value.shouldBeEmpty()
    }

    @Test
    fun nextUnusedColor_wraps_once_every_swatch_is_taken() {
        val taken = LabelColor.PALETTE.map { it.key }
        AnnotationLabelsRepositoryImpl.nextUnusedColor(taken) shouldBe LabelColor.GREEN
        AnnotationLabelsRepositoryImpl.nextUnusedColor(taken + "green") shouldBe LabelColor.TEAL
    }

    @Test
    fun scoreboard_and_clip_flows_partition_the_palette() = runTest {
        val rows = """
          [
            {"id":"l1","name":"Good shot","color_key":"green","created_at":"2026-08-24T12:00:00Z","usage":"both"},
            {"id":"l2","name":"Serve","color_key":"blue","created_at":"2026-08-24T12:00:01Z","usage":"scoreboard"},
            {"id":"l3","name":"Footwork","color_key":"teal","created_at":"2026-08-24T12:00:02Z","usage":"clips"}
          ]
        """.trimIndent()
        val client = TestSupabase.client { jsonResponse(rows) }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())
        repo.refresh()

        // scoreboardLabels and clipLabels are shared on `scope + Dispatchers.Unconfined`
        // (see the doc comments on AnnotationLabelsRepositoryImpl.scope and
        // .scoreboardLabels), specifically so a mutation is visible synchronously:
        // by the time refresh() returns from publish(), both filtered flows already
        // hold the new value, no dispatcher hop pending. first { predicate } is not
        // waiting out a race here - it is used because it doubles as the assertion,
        // reading the already-current value and failing with a clear predicate if the
        // partition is ever wrong.
        repo.scoreboardLabels.first { it.size == 2 }.map { it.id } shouldBe listOf("l1", "l2")
        repo.clipLabels.first { it.size == 2 }.map { it.id } shouldBe listOf("l1", "l3")
    }

    @Test
    fun an_unknown_usage_lands_in_both_flows() = runTest {
        // Fail-safe: a label written by a newer build is still reachable rather
        // than silently absent from every picker.
        val row = """
          [{"id":"l9","name":"Drive","color_key":"red","created_at":"2026-08-24T12:00:00Z","usage":"courtside"}]
        """.trimIndent()
        val client = TestSupabase.client { jsonResponse(row) }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())
        repo.refresh()

        repo.scoreboardLabels.first { it.size == 1 }.map { it.id } shouldBe listOf("l9")
        repo.clipLabels.first { it.size == 1 }.map { it.id } shouldBe listOf("l9")
    }

    @Test
    fun set_usage_moves_a_label_between_the_flows() = runTest {
        // Seeded scoreboard-only, not both: a both-scoped label already sits on
        // both flows before setUsage runs, so moving it to CLIPS would leave the
        // clip side's assertion trivially true and prove nothing about it.
        // Starting scoreboard-only gives both sides a real transition to await -
        // scoreboard populated to empty, clips empty to populated.
        val row = """
          [{"id":"l1","name":"Good shot","color_key":"green","created_at":"2026-08-24T12:00:00Z","usage":"scoreboard"}]
        """.trimIndent()
        val client = TestSupabase.client { jsonResponse(row) }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())
        repo.refresh()
        repo.scoreboardLabels.first { it.size == 1 } // waits for the refresh to land

        repo.setUsage("l1", LabelUsage.CLIPS).isSuccess shouldBe true

        // Awaiting the transition on each side, not the plain .value: scoreboard
        // starts out empty before the refresh above lands too, so an unawaited
        // isEmpty() check could pass without setUsage having done anything.
        repo.scoreboardLabels.first { it.isEmpty() }
        repo.clipLabels.first { it.map { l -> l.id } == listOf("l1") }
    }

    @Test
    fun set_usage_persists_through_the_cache() = runTest {
        val settings = MapSettings()
        val row = """
          [{"id":"l1","name":"Good shot","color_key":"green","created_at":"2026-08-24T12:00:00Z","usage":"both"}]
        """.trimIndent()
        val warmClient = TestSupabase.client(settings) { request ->
            if (request.url.encodedPath.contains("/token")) jsonResponse(tokenResponseFor("u1"))
            else jsonResponse(row)
        }
        warmClient.signInAs("u1")
        val warm = AnnotationLabelsRepositoryImpl(warmClient, settings)
        warm.refresh()
        warm.setUsage("l1", LabelUsage.SCOREBOARD).isSuccess shouldBe true

        // A cold start, offline: the scope has to come back off disk with the
        // label, or the board would forget the choice every launch.
        val coldClient = TestSupabase.client(settings) { request ->
            if (request.url.encodedPath.contains("/token")) jsonResponse(tokenResponseFor("u1"))
            else error("offline")
        }
        coldClient.signInAs("u1")
        val cold = AnnotationLabelsRepositoryImpl(coldClient, settings)
        cold.labels.value.single().scope shouldBe LabelUsage.SCOREBOARD
    }
}

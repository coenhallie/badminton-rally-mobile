package com.badmintontracker.shared.invites

import com.badmintontracker.shared.exercises.DrillEffort
import com.badmintontracker.shared.exercises.DrillProgressRepository
import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.prefs.RacketArm
import com.badmintontracker.shared.prefs.RacketArmPreferenceRepository
import com.badmintontracker.shared.testing.TestSupabase
import com.badmintontracker.shared.testing.jsonResponse
import com.russhwolf.settings.MapSettings
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class DrillSyncAndIsolationTest {

    private fun tokenResponseFor(userId: String) = """
        {
          "access_token":"jwt-$userId","token_type":"bearer","expires_in":3600,
          "refresh_token":"refresh-$userId",
          "user":{"id":"$userId","aud":"authenticated","role":"authenticated",
                  "email":"$userId@example.com","created_at":"2026-11-23T12:00:00Z",
                  "updated_at":"2026-11-23T12:00:00Z"}
        }
    """.trimIndent()

    @Test
    fun drillProgress_pushes_dirty_sessions_merges_remote_and_syncs_deletes() = runTest {
        val upsertedBodies = mutableListOf<String>()
        val preferHeaders = mutableListOf<String>()
        val deletedUrls = mutableListOf<String>()
        var localIdToEcho: String? = null
        val settings = MapSettings()
        val coachRowJson = """
            {
              "id":"aaaaaaaa-0000-0000-0000-000000000010",
              "player_id":"bbbbbbbb-0000-0000-0000-000000000001",
              "logged_by_user_id":"coach-1",
              "exercise_id":"split-squat-endurance",
              "match_id":null,
              "completed_at":"2026-11-23T09:00:00Z",
              "sets_completed":3,
              "sets_target":3,
              "duration_seconds":180,
              "effort":"solid",
              "note":"Coach logged"
            }
        """.trimIndent()

        val client = TestSupabase.client(settings) { req ->
            val path = req.url.encodedPath
            when {
                path.contains("/token") -> jsonResponse(tokenResponseFor("player-1"))
                path.endsWith("/drill_session_logs") && req.method == HttpMethod.Post -> {
                    upsertedBodies += (req.body as? TextContent)?.text.orEmpty()
                    preferHeaders += req.headers["Prefer"].orEmpty()
                    jsonResponse("[]")
                }
                path.endsWith("/drill_session_logs") && req.method == HttpMethod.Delete -> {
                    deletedUrls += req.url.toString()
                    localIdToEcho = null
                    jsonResponse("[]")
                }
                path.endsWith("/drill_session_logs") && req.method == HttpMethod.Get -> {
                    val echoed = localIdToEcho?.let { id ->
                        """,{
                          "id":"$id",
                          "player_id":"bbbbbbbb-0000-0000-0000-000000000001",
                          "logged_by_user_id":"player-1",
                          "exercise_id":"base-mark-6-corner-shadow",
                          "match_id":"11111111-0000-0000-0000-000000000001",
                          "completed_at":"2026-11-23T10:00:00Z",
                          "sets_completed":4,
                          "sets_target":4,
                          "duration_seconds":240,
                          "effort":"hard",
                          "note":"Good pace"
                        }"""
                    }.orEmpty()
                    jsonResponse("[$coachRowJson$echoed]")
                }
                else -> jsonResponse("[]")
            }
        }
        client.auth.signInWith(Email) { email = "player-1@example.com"; password = "pw" }

        val repo = DrillProgressRepository(client, settings) { 1_763_900_000_000L }
        val local = repo.recordSession(
            exerciseId = "base-mark-6-corner-shadow",
            playerId = "bbbbbbbb-0000-0000-0000-000000000001",
            matchId = "11111111-0000-0000-0000-000000000001",
            setsCompleted = 4,
            setsTarget = 4,
            durationSeconds = 240,
            effort = DrillEffort.HARD,
            note = "Good pace",
        )
        localIdToEcho = local.id

        repo.sync().isSuccess shouldBe true
        upsertedBodies.size shouldBe 1
        upsertedBodies.single().shouldContain(local.id)
        upsertedBodies.single().shouldContain("\"effort\":\"hard\"")
        preferHeaders.single().shouldContain("ignore-duplicates")

        // Remote coach-logged session is now visible alongside the player's local session.
        repo.logs.value.any { it.id == "aaaaaaaa-0000-0000-0000-000000000010" && it.loggedByUserId == "coach-1" } shouldBe true
        repo.logs.value.any { it.id == local.id } shouldBe true

        // Deleting a cloud-backed session queues a server delete on next sync;
        // deleting a local-only (unassigned) session does NOT issue a server delete.
        val localOnly = repo.recordSession(
            exerciseId = "base-mark-6-corner-shadow",
            playerId = null,
            matchId = null,
            setsCompleted = 1,
            setsTarget = 1,
            durationSeconds = 60,
            effort = DrillEffort.EASY,
            note = "",
        )
        repo.deleteSession(localOnly.id)
        repo.deleteSession(local.id)
        repo.sync().isSuccess shouldBe true
        deletedUrls.any { it.contains(local.id) } shouldBe true
        deletedUrls.none { it.contains(localOnly.id) } shouldBe true
    }

    @Test
    fun drillProgress_sync_marks_pushed_rows_clean_even_when_pull_fails_and_isolates_rls_rejections() = runTest {
        val settings = MapSettings()
        val unlinkedPlayer = "cccccccc-0000-0000-0000-000000000001"
        val validPlayer = "bbbbbbbb-0000-0000-0000-000000000002"
        var postCount = 0
        var failGet = true
        val acceptedBodies = mutableListOf<String>()

        val client = TestSupabase.client(settings) { req ->
            val path = req.url.encodedPath
            when {
                path.contains("/token") -> jsonResponse(tokenResponseFor("coach-1"))
                path.endsWith("/drill_session_logs") && req.method == HttpMethod.Post -> {
                    postCount++
                    val body = (req.body as? TextContent)?.text.orEmpty()
                    if (body.contains(unlinkedPlayer)) {
                        respondError(
                            status = HttpStatusCode.Forbidden,
                            content = """{"code":"42501","message":"new row violates row-level security policy"}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        acceptedBodies += body
                        jsonResponse("[]")
                    }
                }
                path.endsWith("/drill_session_logs") && req.method == HttpMethod.Get -> {
                    if (failGet) {
                        respondError(
                            status = HttpStatusCode.InternalServerError,
                            content = """{"message":"transient"}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        jsonResponse("[]")
                    }
                }
                else -> jsonResponse("[]")
            }
        }
        client.auth.signInWith(Email) { email = "coach-1@example.com"; password = "pw" }

        val repo = DrillProgressRepository(client, settings) { 1_763_900_000_000L }
        val rejected = repo.recordSession("ex-1", unlinkedPlayer, null, 1, 1, 60, DrillEffort.EASY, "")
        val valid = repo.recordSession("ex-2", validPlayer, null, 2, 2, 120, DrillEffort.SOLID, "")

        // Batch fails with 42501 -> per-row fallback skips rejected, pushes valid, marks both clean
        // BEFORE GET fails.
        repo.sync().isFailure shouldBe true
        acceptedBodies.size shouldBe 1
        acceptedBodies.single().shouldContain(valid.id)
        val postsAfterFirstSync = postCount

        // Next sync succeeds on GET and does NOT re-push either row.
        failGet = false
        repo.sync().isSuccess shouldBe true
        postCount shouldBe postsAfterFirstSync

        // forgetPlayer drops local logs for a deleted player immediately.
        val deletedPlayer = "dddddddd-0000-0000-0000-000000000003"
        repo.recordSession("ex-3", deletedPlayer, null, 1, 1, 60, DrillEffort.HARD, "")
        repo.logs.value.any { it.playerId == deletedPlayer } shouldBe true
        repo.forgetPlayer(deletedPlayer)
        repo.logs.value.none { it.playerId == deletedPlayer } shouldBe true
    }

    @Test
    fun local_stores_isolate_per_account_and_migrate_legacy_data_to_first_user() {
        val settings = MapSettings()
        // Pre-upgrade un-scoped coach data stored by an older app build.
        val legacyVideos = LocalVideoRepository(settings)
        legacyVideos.add(
            LocalVideoEntry(
                id = "v-legacy",
                uri = "content://v-legacy",
                displayName = "legacy.mp4",
                durationMs = 10_000,
                sizeBytes = 500,
                addedAtEpochMs = 1000L,
            )
        )
        val legacyNotes = LocalAnnotationsRepository(settings)
        legacyNotes.add("v-legacy", 2.0f, "Nice clear", null)
        val legacyArm = RacketArmPreferenceRepository(settings)
        legacyArm.setRacketArm("v-legacy", RacketArm.LEFT)

        // Upgrade: first signed-in account (coach-1) adopts legacy data.
        var activeUser: String? = "coach-1"
        val videos = LocalVideoRepository(settings, ownerId = { activeUser })
        val notes = LocalAnnotationsRepository(settings, ownerId = { activeUser })
        val arm = RacketArmPreferenceRepository(settings, ownerId = { activeUser })

        videos.entries.value.map { it.id } shouldBe listOf("v-legacy")
        notes.annotationsFor("v-legacy") shouldHaveSize 1
        arm.racketArm("v-legacy") shouldBe RacketArm.LEFT

        // Switching to player-1 hides coach-1's local data completely.
        activeUser = "player-1"
        videos.reloadForCurrentOwner()
        notes.reloadForCurrentOwner()
        videos.entries.value.shouldBeEmpty()
        notes.annotationsFor("v-legacy").shouldBeEmpty()
        arm.racketArm("v-legacy").shouldBeNull()

        // Switching back to coach-1 restores coach-1's local data intact.
        activeUser = "coach-1"
        videos.reloadForCurrentOwner()
        notes.reloadForCurrentOwner()
        videos.entries.value.map { it.id } shouldBe listOf("v-legacy")
        notes.annotationsFor("v-legacy") shouldHaveSize 1
        arm.racketArm("v-legacy") shouldBe RacketArm.LEFT
    }

    @Test
    fun allPersistedUris_unions_all_accounts_so_orphan_sweep_never_deletes_other_accounts_videos() {
        val settings = MapSettings()
        var activeUser: String? = "coach-1"
        val videos = LocalVideoRepository(settings, ownerId = { activeUser })
        videos.add(
            LocalVideoEntry(
                id = "v-coach",
                uri = "LocalVideos/coach.mp4",
                displayName = "coach.mp4",
                durationMs = 10_000,
                sizeBytes = 500,
                addedAtEpochMs = 1000L,
            )
        )

        activeUser = "player-1"
        videos.reloadForCurrentOwner()
        videos.add(
            LocalVideoEntry(
                id = "v-player",
                uri = "LocalVideos/player.mp4",
                displayName = "player.mp4",
                durationMs = 10_000,
                sizeBytes = 500,
                addedAtEpochMs = 2000L,
            )
        )

        // Signed-out startup must still report every account's persisted URIs.
        activeUser = null
        videos.reloadForCurrentOwner()
        videos.entries.value.shouldBeEmpty()
        videos.allPersistedUris().toSet() shouldBe setOf("LocalVideos/coach.mp4", "LocalVideos/player.mp4")
    }

    @Test
    fun racketArm_clearing_pre_upgrade_entry_removes_legacy_key_so_it_never_resurrects() {
        val settings = MapSettings()
        RacketArmPreferenceRepository(settings).setRacketArm("v-legacy", RacketArm.LEFT)

        val scoped = RacketArmPreferenceRepository(settings, ownerId = { "coach-1" })
        // Clear BEFORE ever reading: must also drop the un-migrated legacy key.
        scoped.setRacketArm("v-legacy", null)
        scoped.racketArm("v-legacy").shouldBeNull()
    }
}

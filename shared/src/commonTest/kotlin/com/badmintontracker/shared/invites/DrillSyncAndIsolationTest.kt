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
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
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
        val deletedUrls = mutableListOf<String>()
        val settings = MapSettings()
        val remoteRow = """
            [{
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
            }]
        """.trimIndent()

        val client = TestSupabase.client(settings) { req ->
            val path = req.url.encodedPath
            when {
                path.contains("/token") -> jsonResponse(tokenResponseFor("player-1"))
                path.endsWith("/drill_session_logs") && req.method == HttpMethod.Post -> {
                    upsertedBodies += (req.body as? TextContent)?.text.orEmpty()
                    jsonResponse("[]")
                }
                path.endsWith("/drill_session_logs") && req.method == HttpMethod.Delete -> {
                    deletedUrls += req.url.toString()
                    jsonResponse("[]")
                }
                path.endsWith("/drill_session_logs") && req.method == HttpMethod.Get -> jsonResponse(remoteRow)
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

        repo.sync().isSuccess shouldBe true
        upsertedBodies.size shouldBe 1
        upsertedBodies.single().shouldContain(local.id)
        upsertedBodies.single().shouldContain("\"effort\":\"hard\"")

        // Remote coach-logged session is now visible alongside the player's local session.
        repo.logs.value.any { it.id == "aaaaaaaa-0000-0000-0000-000000000010" && it.loggedByUserId == "coach-1" } shouldBe true

        // Deleting a session queues a server delete on next sync.
        repo.deleteSession(local.id)
        repo.sync().isSuccess shouldBe true
        deletedUrls.any { it.contains(local.id) } shouldBe true
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
}

package com.badmintontracker.shared.invites

import com.badmintontracker.shared.prefs.RacketArm
import com.badmintontracker.shared.repo.AccountRole
import com.badmintontracker.shared.repo.AuthRepositoryImpl
import com.badmintontracker.shared.testing.TestSupabase
import com.badmintontracker.shared.testing.jsonResponse
import com.russhwolf.settings.MapSettings
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class PlayerAccessRepositoryTest {

    private fun tokenResponseFor(userId: String, accountRole: String? = null): String {
        val appMeta = if (accountRole == null) "{}" else """{"account_role":"$accountRole"}"""
        return """
            {
              "access_token":"jwt-$userId","token_type":"bearer","expires_in":3600,
              "refresh_token":"refresh-$userId",
              "user":{"id":"$userId","aud":"authenticated","role":"authenticated",
                      "email":"$userId@example.com","app_metadata":$appMeta,
                      "created_at":"2026-11-23T12:00:00Z","updated_at":"2026-11-23T12:00:00Z"}
            }
        """.trimIndent()
    }

    private suspend fun signedIn(
        userId: String,
        accountRole: String? = null,
        settings: MapSettings = MapSettings(),
        handler: MockRequestHandler,
    ): SupabaseClient {
        val client = TestSupabase.client(settings) { req ->
            if (req.url.encodedPath.contains("/token")) jsonResponse(tokenResponseFor(userId, accountRole))
            else handler(req)
        }
        client.auth.signInWith(Email) {
            email = "$userId@example.com"
            password = "password"
        }
        return client
    }

    @Test
    fun authRepository_detects_coach_and_player_roles_from_app_metadata() = runTest {
        val coachClient = signedIn("coach1", accountRole = null) { jsonResponse("[]") }
        AuthRepositoryImpl(coachClient).accountRole() shouldBe AccountRole.COACH

        val playerClient = signedIn("player1", accountRole = "player") { jsonResponse("[]") }
        AuthRepositoryImpl(playerClient).accountRole() shouldBe AccountRole.PLAYER
    }

    @Test
    fun coach_refreshes_access_states_creates_and_revokes_invite_and_unlinks() = runTest {
        val calls = mutableListOf<String>()
        val client = signedIn("coach1") { req ->
            val path = req.url.encodedPath
            calls += path
            when {
                path.endsWith("/rpc/list_player_access_states") -> jsonResponse(
                    """[
                      {"player_id":"p1","status":"uninvited","linked_email":null,"linked_at":null,"pending_code":null,"pending_expires_at":null},
                      {"player_id":"p2","status":"linked","linked_email":"marco@club.nl","linked_at":"2026-11-23T10:00:00Z","pending_code":null,"pending_expires_at":null}
                    ]"""
                )
                path.endsWith("/rpc/create_player_invite") -> jsonResponse(
                    """[{"invite_id":"inv1","code":"K7M4QP9R","expires_at":"2026-11-30T10:00:00Z"}]"""
                )
                path.endsWith("/rpc/revoke_player_invite") -> jsonResponse("null")
                path.endsWith("/rpc/unlink_player_login") -> jsonResponse("null")
                else -> jsonResponse("[]")
            }
        }
        val repo = PlayerAccessRepositoryImpl(client, MapSettings())

        repo.refreshAccessStates().isSuccess shouldBe true
        repo.accessByPlayerId.value["p1"]?.status shouldBe PlayerAccessStatus.UNINVITED
        repo.accessByPlayerId.value["p2"]?.status shouldBe PlayerAccessStatus.LINKED
        repo.accessByPlayerId.value["p2"]?.linkedEmail shouldBe "marco@club.nl"

        val created = repo.createInvite("p1").getOrThrow()
        created.code shouldBe "K7M4QP9R"
        repo.accessByPlayerId.value["p1"]?.status shouldBe PlayerAccessStatus.PENDING
        repo.accessByPlayerId.value["p1"]?.pendingCode shouldBe "K7M4QP9R"

        repo.revokeInvite("p1").isSuccess shouldBe true
        repo.accessByPlayerId.value["p1"]?.status shouldBe PlayerAccessStatus.UNINVITED

        repo.unlinkPlayer("p2").isSuccess shouldBe true
        repo.accessByPlayerId.value["p2"]?.status shouldBe PlayerAccessStatus.UNINVITED
        calls.any { it.endsWith("/rpc/unlink_player_login") } shouldBe true
    }

    @Test
    fun previewInvite_calls_edge_function_and_maps_errors() = runTest {
        var capturedBody: String? = null
        val client = TestSupabase.client { req ->
            capturedBody = (req.body as? TextContent)?.text
            if (capturedBody?.contains("K7M4QP9R") == true) {
                jsonResponse("""{"playerName":"Marco","racketArm":"right","coachEmail":"coach@club.nl"}""")
            } else {
                respondError(
                    status = HttpStatusCode.BadRequest,
                    content = """{"error":"invalid_or_expired"}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val repo = PlayerAccessRepositoryImpl(client, MapSettings())

        val ok = repo.previewInvite("k7m4-qp9r").getOrThrow()
        ok.playerName shouldBe "Marco"
        ok.racketArm shouldBe RacketArm.RIGHT
        ok.coachEmail shouldBe "coach@club.nl"
        capturedBody!!.shouldContain("\"action\":\"preview\"")

        val bad = repo.previewInvite("ZZZZ-ZZZZ").exceptionOrNull() as InviteException
        bad.kind shouldBe InviteErrorKind.INVALID_OR_EXPIRED
    }

    @Test
    fun redeemInviteNewAccount_calls_edge_function_then_signs_in_and_maps_email_exists() = runTest {
        var signedInEmail: String? = null
        val client = TestSupabase.client { req ->
            val path = req.url.encodedPath
            val body = (req.body as? TextContent)?.text.orEmpty()
            when {
                path.contains("/functions/v1/redeem-player-invite") && body.contains("taken@club.nl") ->
                    respondError(
                        status = HttpStatusCode.Conflict,
                        content = """{"error":"email_exists"}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                path.contains("/functions/v1/redeem-player-invite") ->
                    jsonResponse("""{"playerId":"p1","playerName":"Marco","coachEmail":"coach@club.nl"}""")
                path.contains("/token") -> {
                    signedInEmail = body
                    jsonResponse(tokenResponseFor("player1", "player"))
                }
                path.endsWith("/rpc/list_my_player_profiles") -> jsonResponse(
                    """[{"player_id":"p1","coach_id":"c1","coach_email":"coach@club.nl","name":"Marco","racket_arm":"right","linked_at":"2026-11-23T12:00:00Z"}]"""
                )
                else -> jsonResponse("[]")
            }
        }
        val repo = PlayerAccessRepositoryImpl(client, MapSettings())

        val existsErr = repo.redeemInviteNewAccount("K7M4-QP9R", "taken@club.nl", "password123")
            .exceptionOrNull() as InviteException
        existsErr.kind shouldBe InviteErrorKind.EMAIL_EXISTS

        val ok = repo.redeemInviteNewAccount("K7M4-QP9R", "marco@club.nl", "password123")
        ok.isSuccess shouldBe true
        signedInEmail!!.shouldContain("marco@club.nl")
        repo.linkedProfiles.value.size shouldBe 1
        repo.linkedProfiles.value.first().name shouldBe "Marco"
    }

    @Test
    fun redeemInviteExistingAccount_calls_authenticated_rpc_and_refreshes_profiles() = runTest {
        val client = signedIn("player1", "player") { req ->
            when {
                req.url.encodedPath.endsWith("/rpc/redeem_player_invite_authenticated") -> jsonResponse(
                    """[{"player_id":"p9","player_name":"Marco","racket_arm":"left","coach_email":"coach2@club.nl"}]"""
                )
                req.url.encodedPath.endsWith("/rpc/list_my_player_profiles") -> jsonResponse(
                    """[{"player_id":"p9","coach_id":"c2","coach_email":"coach2@club.nl","name":"Marco","racket_arm":"left","linked_at":"2026-11-23T12:00:00Z"}]"""
                )
                else -> jsonResponse("[]")
            }
        }
        val repo = PlayerAccessRepositoryImpl(client, MapSettings())

        val linked = repo.redeemInviteExistingAccount("K7M4-QP9R").getOrThrow()
        linked.playerId shouldBe "p9"
        linked.coachEmail shouldBe "coach2@club.nl"
        repo.linkedProfiles.value.map { it.playerId } shouldBe listOf("p9")
    }

    @Test
    fun linked_profiles_and_access_states_clear_on_clear_and_isolate_by_owner() = runTest {
        val settings = MapSettings()
        val client1 = signedIn("player1", "player", settings) { req ->
            when {
                req.url.encodedPath.endsWith("/rpc/list_my_player_profiles") -> jsonResponse(
                    """[{"player_id":"p1","coach_id":"c1","coach_email":"coach@club.nl","name":"Marco","racket_arm":null,"linked_at":"2026-11-23T12:00:00Z"}]"""
                )
                else -> jsonResponse("[]")
            }
        }
        val repo1 = PlayerAccessRepositoryImpl(client1, settings)
        repo1.refreshLinkedProfiles().isSuccess shouldBe true
        repo1.linkedProfiles.value.size shouldBe 1

        // Different user must not see player1's cached profiles.
        val client2 = signedIn("player2", "player", MapSettings()) { jsonResponse("[]") }
        val repo2 = PlayerAccessRepositoryImpl(client2, settings)
        repo2.linkedProfiles.value.shouldBeEmpty()

        repo1.clear()
        repo1.linkedProfiles.value.shouldBeEmpty()
        repo1.accessByPlayerId.value.shouldBeEmpty()
    }
}

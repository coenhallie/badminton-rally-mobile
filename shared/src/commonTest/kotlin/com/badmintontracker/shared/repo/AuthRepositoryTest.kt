package com.badmintontracker.shared.repo

import app.cash.turbine.test
import com.badmintontracker.shared.testing.TestSupabase
import com.badmintontracker.shared.testing.jsonResponse
import io.github.jan.supabase.auth.status.SessionStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class AuthRepositoryTest {
    @Test
    fun fresh_client_settles_in_NotAuthenticated() = runTest {
        val client = TestSupabase.client { _ ->
            respond("", HttpStatusCode.OK)
        }
        val repo = AuthRepositoryImpl(client)
        repo.sessionFlow.test {
            // Initializing is a real state of supabase-kt's sessionStatus, not
            // noise: the client sits in it while it looks for a stored session,
            // and how long that takes is a platform question. Asserting on the
            // first emission made this pass on the JVM, where the lookup had
            // already finished, and fail on iOS, where it had not - a result
            // about scheduling rather than about auth.
            //
            // Waiting for the status to settle is the claim actually worth
            // holding: a client with nothing stored ends up NotAuthenticated. A
            // client that never leaves Initializing still fails here, on
            // awaitItem's timeout, so this does not weaken into "eventually
            // anything".
            var status = awaitItem()
            while (status is SessionStatus.Initializing) status = awaitItem()
            status.shouldBeInstanceOf<SessionStatus.NotAuthenticated>()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun currentUserId_returns_null_when_not_signed_in() = runTest {
        val client = TestSupabase.client { _ -> respond("", HttpStatusCode.OK) }
        val repo = AuthRepositoryImpl(client)
        repo.currentUserId() shouldBe null
    }

    @Test
    fun signInEmail_calls_token_grant_endpoint() = runTest {
        val tokenResponse = """
            {
              "access_token":"jwt-abc","token_type":"bearer","expires_in":3600,
              "refresh_token":"refresh-1",
              "user":{"id":"u1","aud":"authenticated","role":"authenticated",
                      "email":"a@b.co","created_at":"2026-05-04T12:00:00Z",
                      "updated_at":"2026-05-04T12:00:00Z"}
            }
        """.trimIndent()
        val requests = mutableListOf<Pair<String, String>>() // path -> body
        val client = TestSupabase.client { request ->
            val body = (request.body as? TextContent)?.text ?: ""
            requests += request.url.encodedPath to body
            jsonResponse(tokenResponse)
        }
        val repo = AuthRepositoryImpl(client)

        val result = repo.signInEmail("a@b.co", "secret")

        result.isSuccess shouldBe true
        val tokenCall = requests.firstOrNull { it.first.contains("/auth/v1/token") }
            ?: error("expected a request to /auth/v1/token, got: ${requests.map { it.first }}")
        tokenCall.second.shouldContain(""""email":"a@b.co"""")
    }
}

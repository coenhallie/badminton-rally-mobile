package com.badmintontracker.shared.repo

import app.cash.turbine.test
import com.badmintontracker.shared.testing.TestSupabase
import io.github.jan.supabase.auth.status.SessionStatus
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class AuthRepositoryTest {
    @Test
    fun fresh_client_starts_in_NotAuthenticated() = runTest {
        val client = TestSupabase.client { _ ->
            respond("", HttpStatusCode.OK)
        }
        val repo = AuthRepositoryImpl(client)
        repo.sessionFlow.test {
            val first = awaitItem()
            first.shouldBeInstanceOf<SessionStatus.NotAuthenticated>()
            cancelAndIgnoreRemainingEvents()
        }
    }
}

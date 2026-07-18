package com.badmintontracker.shared.repo

import com.badmintontracker.shared.testing.TestSupabase
import com.badmintontracker.shared.testing.jsonResponse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class SharesRepositoryTest {

    @Test
    fun leaveShare_calls_leave_shared_match_rpc_with_video_id() = runTest {
        var captured: Pair<String, String>? = null
        val client = TestSupabase.client { request ->
            captured = request.url.encodedPath to ((request.body as? TextContent)?.text ?: "")
            jsonResponse("null")
        }
        val repo = SharesRepositoryImpl(client)

        val result = repo.leaveShare("v1")

        result.isSuccess shouldBe true
        captured!!.first shouldContain "rpc/leave_shared_match"
        captured!!.second shouldContain """"p_video_id":"v1""""
    }

    @Test
    fun leaveShare_failure_returns_failure() = runTest {
        val client = TestSupabase.client { _ ->
            jsonResponse("""{"message":"boom"}""", HttpStatusCode.InternalServerError)
        }
        val repo = SharesRepositoryImpl(client)

        repo.leaveShare("v1").isFailure shouldBe true
    }
}

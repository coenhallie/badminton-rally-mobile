package com.badmintontracker.shared.repo

import com.badmintontracker.shared.testing.TestSupabase
import com.badmintontracker.shared.testing.jsonResponse
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test

class SharesRepositoryTest {

    @Test
    fun share_calls_share_match_rpc_with_video_id_and_email() = runTest {
        var capturedUrl: String? = null
        var capturedBody: String? = null
        val client = TestSupabase.client { req ->
            capturedUrl = req.url.toString()
            capturedBody = (req.body as? TextContent)?.text
            jsonResponse("\"00000000-0000-0000-0000-000000000001\"")
        }
        val repo = SharesRepositoryImpl(client)

        val result = repo.share(videoId = "v1", email = "coach@example.com")

        result.isSuccess shouldBe true
        capturedUrl!!.shouldContain("/rest/v1/rpc/share_match")
        capturedBody!!.shouldContain("\"p_video_id\":\"v1\"")
        capturedBody!!.shouldContain("\"p_email\":\"coach@example.com\"")
    }

    @Test
    fun share_maps_no_such_user_error() = runTest {
        val client = TestSupabase.client { _ ->
            respondError(
                status = HttpStatusCode.BadRequest,
                content = """{"code":"P0003","message":"no_such_user"}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = SharesRepositoryImpl(client)

        val result = repo.share("v1", "ghost@example.com")

        (result.exceptionOrNull() is ShareError.NoSuchUser) shouldBe true
    }

    @Test
    fun share_maps_not_owner_error() = runTest {
        val client = TestSupabase.client { _ ->
            respondError(
                status = HttpStatusCode.BadRequest,
                content = """{"code":"P0002","message":"not_owner"}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val result = SharesRepositoryImpl(client).share("v1", "x@y")
        (result.exceptionOrNull() is ShareError.NotOwner) shouldBe true
    }

    @Test
    fun share_maps_self_share_error() = runTest {
        val client = TestSupabase.client { _ ->
            respondError(
                status = HttpStatusCode.BadRequest,
                content = """{"code":"P0004","message":"cannot_share_with_self"}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val result = SharesRepositoryImpl(client).share("v1", "me@example.com")
        (result.exceptionOrNull() is ShareError.CannotShareSelf) shouldBe true
    }

    @Test
    fun listShares_decodes_rpc_response() = runTest {
        val client = TestSupabase.client { _ ->
            jsonResponse("""[
              {"shared_with_user_id":"u1","email":"a@x","created_at":"2026-05-06T12:00:00Z"},
              {"shared_with_user_id":"u2","email":"b@x","created_at":"2026-05-06T12:01:00Z"}
            ]""")
        }
        val result = SharesRepositoryImpl(client).listShares("v1")
        result.getOrThrow() shouldHaveSize 2
        result.getOrThrow()[0].email shouldBe "a@x"
    }

    @Test
    fun listReceived_decodes_rpc_response() = runTest {
        var capturedUrl: String? = null
        val client = TestSupabase.client { req ->
            capturedUrl = req.url.toString()
            jsonResponse("""[
              {"video_id":"v1","sharer_email":"alice@example.com","shared_at":"2026-05-07T10:00:00Z"},
              {"video_id":"v2","sharer_email":"bob@example.com","shared_at":"2026-05-08T11:00:00Z"}
            ]""")
        }

        val result = SharesRepositoryImpl(client).listReceived()

        capturedUrl!!.shouldContain("/rest/v1/rpc/list_received_match_shares")
        result shouldHaveSize 2
        result[0].videoId shouldBe "v1"
        result[0].sharerEmail shouldBe "alice@example.com"
        result[0].sharedAt shouldBe Instant.parse("2026-05-07T10:00:00Z")
        result[1].sharerEmail shouldBe "bob@example.com"
    }

    @Test
    fun listReceived_handles_null_sharer_email() = runTest {
        val client = TestSupabase.client { _ ->
            jsonResponse("""[
              {"video_id":"v1","sharer_email":null,"shared_at":"2026-05-07T10:00:00Z"},
              {"video_id":"v2","sharer_email":"bob@example.com","shared_at":"2026-05-08T11:00:00Z"}
            ]""")
        }

        val result = SharesRepositoryImpl(client).listReceived()

        result shouldHaveSize 2
        result[0].sharerEmail shouldBe null
        result[1].sharerEmail shouldBe "bob@example.com"
    }

    @Test
    fun unshare_calls_unshare_match_rpc() = runTest {
        var capturedUrl: String? = null
        var capturedBody: String? = null
        val client = TestSupabase.client { req ->
            capturedUrl = req.url.toString()
            capturedBody = (req.body as? TextContent)?.text
            jsonResponse("null")
        }
        val result = SharesRepositoryImpl(client).unshare("v1", "u1")
        result.isSuccess shouldBe true
        capturedUrl!!.shouldContain("/rest/v1/rpc/unshare_match")
        capturedBody!!.shouldContain("\"p_video_id\":\"v1\"")
        capturedBody!!.shouldContain("\"p_user_id\":\"u1\"")
    }
}

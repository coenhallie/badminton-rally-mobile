package com.badmintontracker.shared.repo

import app.cash.turbine.test
import com.badmintontracker.shared.model.CourtKeypoints
import com.badmintontracker.shared.testing.TestSupabase
import com.badmintontracker.shared.testing.jsonResponse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.respondError
import io.ktor.content.TextContent
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

private fun testKeypoints() = CourtKeypoints(
    topLeft = listOf(1f, 2f), topRight = listOf(3f, 4f),
    bottomRight = listOf(5f, 6f), bottomLeft = listOf(7f, 8f),
    netLeft = listOf(9f, 10f), netRight = listOf(11f, 12f),
    serviceLineNearLeft = listOf(13f, 14f), serviceLineNearRight = listOf(15f, 16f),
    serviceLineFarLeft = listOf(17f, 18f), serviceLineFarRight = listOf(19f, 20f),
    centerNear = listOf(21f, 22f), centerFar = listOf(23f, 24f),
)

class VideosRepositoryTest {

    @Test
    fun createVideo_fails_cleanly_when_signed_out() = runTest {
        // No session in the test client: owner_id comes from auth.currentUserOrNull().
        val client = TestSupabase.client { jsonResponse("[]", HttpStatusCode.Created) }
        val repo = VideosRepositoryImpl(client)
        repo.createVideo("vid-1", "match.mp4", 123L).isFailure.shouldBeTrue()
    }

    @Test
    fun setCourtKeypoints_patches_manual_court_keypoints_json() = runTest {
        var body = ""
        var method = HttpMethod.Get
        var query = ""
        val client = TestSupabase.client { request ->
            method = request.method
            query = request.url.encodedQuery
            body = (request.body as TextContent).text
            jsonResponse("[]")
        }
        val repo = VideosRepositoryImpl(client)
        repo.setCourtKeypoints("vid-1", testKeypoints()).isSuccess.shouldBeTrue()
        method shouldBe HttpMethod.Patch
        query shouldContain "id=eq.vid-1"
        body shouldContain "\"manual_court_keypoints\":{\"top_left\":[1.0,2.0]"
        body shouldContain "\"center_far\":[23.0,24.0]"
    }

    @Test
    fun startProcessing_posts_to_edge_function_with_video_id() = runTest {
        var path = ""
        var body = ""
        val client = TestSupabase.client { request ->
            path = request.url.encodedPath
            body = (request.body as TextContent).text
            jsonResponse("""{"ok":true}""")
        }
        val repo = VideosRepositoryImpl(client)
        repo.startProcessing("vid-1").isSuccess.shouldBeTrue()
        path shouldContain "/functions/v1/process-video"
        body shouldContain "\"video_id\":\"vid-1\""
    }

    @Test
    fun startProcessing_maps_non_2xx_to_failure() = runTest {
        val client = TestSupabase.client {
            respondError(HttpStatusCode.Conflict, "already processing")
        }
        val repo = VideosRepositoryImpl(client)
        repo.startProcessing("vid-1").isFailure.shouldBeTrue()
    }

    @Test
    fun html_error_body_surfaces_http_status_instead_of_unknown_error() = runTest {
        // Edge/CDN hiccups return HTML, which postgrest can't parse into an error.
        val client = TestSupabase.client {
            respondError(
                HttpStatusCode.BadRequest,
                "<!DOCTYPE html><html><body>Attention Required</body></html>",
            )
        }
        val repo = VideosRepositoryImpl(client)
        val error = repo.setCourtKeypoints("vid-1", testKeypoints()).exceptionOrNull()
        (error?.message ?: "") shouldContain "HTTP 400"
    }

    @Test
    fun observeProcessing_emits_until_terminal_status() = runTest {
        var call = 0
        val client = TestSupabase.client {
            call++
            when {
                // DB stores progress as a 0..100 percentage.
                call <= 1 -> jsonResponse("""[{"status":"processing_phase1","progress":40.0,"error":null}]""")
                else      -> jsonResponse("""[{"status":"phase1_complete","progress":100.0,"error":null}]""")
            }
        }
        val repo = VideosRepositoryImpl(client)
        repo.observeProcessing("vid-1", pollIntervalMs = 1).test {
            // …surfaced to the UI normalized to 0..1.
            awaitItem() shouldBe ProcessingUpdate("processing_phase1", 0.4f, null)
            val last = awaitItem()
            last shouldBe ProcessingUpdate("phase1_complete", 1.0f, null)
            last.isSuccess.shouldBeTrue()
            awaitComplete()
        }
    }

    @Test
    fun observeProcessing_survives_transient_poll_errors() = runTest {
        var call = 0
        val client = TestSupabase.client {
            call++
            when {
                call <= 1 -> respondError(HttpStatusCode.ServiceUnavailable, "blip")
                else      -> jsonResponse("""[{"status":"phase1_complete","progress":100.0,"error":null}]""")
            }
        }
        val repo = VideosRepositoryImpl(client)
        repo.observeProcessing("vid-1", pollIntervalMs = 1).test {
            val last = awaitItem()
            last shouldBe ProcessingUpdate("phase1_complete", 1.0f, null)
            awaitComplete()
        }
    }

    @Test
    fun observeProcessing_reports_failure_after_persistent_poll_errors() = runTest {
        val client = TestSupabase.client {
            respondError(HttpStatusCode.ServiceUnavailable, "down")
        }
        val repo = VideosRepositoryImpl(client)
        repo.observeProcessing("vid-1", pollIntervalMs = 1).test {
            val last = awaitItem()
            last.isFailure.shouldBeTrue()
            last.status shouldBe "failed_connection"
            awaitComplete()
        }
    }

    @Test
    fun processing_update_classifies_statuses() {
        ProcessingUpdate("failed_phase1", null, "boom").isFailure.shouldBeTrue()
        ProcessingUpdate("failed_phase2", null, null).isFailure.shouldBeTrue()
        ProcessingUpdate("failed", null, null).isFailure.shouldBeTrue()
        ProcessingUpdate("completed", null, null).isSuccess.shouldBeTrue()
        ProcessingUpdate("phase1_complete", 1f, null).isSuccess.shouldBeTrue()
        ProcessingUpdate("processing_phase2", 0.2f, null).isTerminal shouldBe false
    }
}

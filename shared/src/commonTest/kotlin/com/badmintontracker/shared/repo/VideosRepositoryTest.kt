package com.badmintontracker.shared.repo

import app.cash.turbine.test
import com.badmintontracker.shared.model.CourtKeypoints
import com.badmintontracker.shared.testing.TestSupabase
import com.badmintontracker.shared.testing.jsonResponse
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.engine.mock.respondError
import io.ktor.content.TextContent
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test

private fun testKeypoints() = CourtKeypoints(
    topLeft = listOf(1f, 2f), topRight = listOf(3f, 4f),
    bottomRight = listOf(5f, 6f), bottomLeft = listOf(7f, 8f),
    netLeft = listOf(9f, 10f), netRight = listOf(11f, 12f),
    serviceLineNearLeft = listOf(13f, 14f), serviceLineNearRight = listOf(15f, 16f),
    serviceLineFarLeft = listOf(17f, 18f), serviceLineFarRight = listOf(19f, 20f),
    centerNear = listOf(21f, 22f), centerFar = listOf(23f, 24f),
)

// Signing in for real is the only deterministic way to make a TestSupabase client
// report a user: currentUserOrNull() reads a StateFlow that a fresh client's own
// storage-restore populates asynchronously. Same helper shape as
// AnnotationLabelsRepositoryTest.
private val tokenResponse = """
    {
      "access_token":"jwt-user-1","token_type":"bearer","expires_in":3600,
      "refresh_token":"refresh-user-1",
      "user":{"id":"user-1","aud":"authenticated","role":"authenticated",
              "email":"user-1@example.com","created_at":"2026-08-25T12:00:00Z",
              "updated_at":"2026-08-25T12:00:00Z"}
    }
""".trimIndent()

private suspend fun SupabaseClient.signIn() {
    auth.signInWith(Email) {
        email = "user-1@example.com"
        password = "password"
    }
}

class VideosRepositoryTest {

    @Test
    fun createVideo_writes_title_and_description_on_the_insert() = runTest {
        // The DB grants no UPDATE on either column, so this insert is the only
        // chance to set them — a dropped field here is unrecoverable for that match.
        var body = ""
        val client = TestSupabase.client { request ->
            if (request.url.encodedPath.contains("/auth/v1/token")) jsonResponse(tokenResponse)
            else {
                body = (request.body as TextContent).text
                jsonResponse("[]", HttpStatusCode.Created)
            }
        }
        client.signIn()
        val repo = VideosRepositoryImpl(client)

        repo.createVideo("vid-1", "match.mp4", 123L, "Thu League vs Marco", "Indoor court 2.")
            .isSuccess.shouldBeTrue()

        body shouldContain "\"title\":\"Thu League vs Marco\""
        body shouldContain "\"description\":\"Indoor court 2.\""
    }

    @Test
    fun createVideo_omits_both_fields_rather_than_sending_empty_strings_when_unset() = runTest {
        // An unset field is left out of the insert entirely, so the column takes its
        // NULL default. What must never appear is "", which videos_title_length_check
        // rejects — surfacing as an opaque CREATE_ROW failure on a video the user
        // never chose to name. LocalVideoDetails.normalize is what maps blank to null.
        var body = ""
        val client = TestSupabase.client { request ->
            if (request.url.encodedPath.contains("/auth/v1/token")) jsonResponse(tokenResponse)
            else {
                body = (request.body as TextContent).text
                jsonResponse("[]", HttpStatusCode.Created)
            }
        }
        client.signIn()
        val repo = VideosRepositoryImpl(client)

        repo.createVideo("vid-1", "match.mp4", 123L, null, null).isSuccess.shouldBeTrue()

        body shouldNotContain "\"title\""
        body shouldNotContain "\"description\""
    }

    @Test
    fun listMatchMetadata_calls_the_rpc_and_decodes_its_rows() = runTest {
        var path = ""
        val client = TestSupabase.client { request ->
            path = request.url.encodedPath
            jsonResponse("""[{"video_id":"vid-1","title":"Thu League","description":null}]""")
        }
        val repo = VideosRepositoryImpl(client)

        val rows = repo.listMatchMetadata().getOrThrow()

        path shouldContain "/rest/v1/rpc/list_match_metadata"
        rows.single().videoId shouldBe "vid-1"
        rows.single().title shouldBe "Thu League"
    }

    @Test
    fun listMatchMetadata_reports_failure_rather_than_an_empty_list() = runTest {
        // The caller's contract is "on failure leave the previous titles alone".
        // A success-with-empty-list here would wipe every title on a blip.
        val client = TestSupabase.client { respondError(HttpStatusCode.ServiceUnavailable, "down") }
        val repo = VideosRepositoryImpl(client)

        repo.listMatchMetadata().isFailure.shouldBeTrue()
    }

    @Test
    fun createVideo_fails_cleanly_when_signed_out() = runTest {
        // No session in the test client: owner_id comes from auth.currentUserOrNull().
        val client = TestSupabase.client { jsonResponse("[]", HttpStatusCode.Created) }
        val repo = VideosRepositoryImpl(client)
        repo.createVideo("vid-1", "match.mp4", 123L, null, null).isFailure.shouldBeTrue()
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
    fun startProcessing_resets_row_status_before_invoking_edge_function() = runTest {
        // A re-trigger after a failed run must clear the terminal failed_* status
        // first — otherwise the client's poll loop instantly re-reads the stale
        // failure before the pipeline overwrites it.
        val requests = mutableListOf<Pair<HttpMethod, String>>()
        var patchBody = ""
        val client = TestSupabase.client { request ->
            requests += request.method to request.url.encodedPath
            if (request.method == HttpMethod.Patch) {
                patchBody = (request.body as TextContent).text
            }
            jsonResponse("""{"ok":true}""")
        }
        val repo = VideosRepositoryImpl(client)
        repo.startProcessing("vid-1").isSuccess.shouldBeTrue()
        val patchIndex = requests.indexOfFirst { it.first == HttpMethod.Patch && it.second.contains("/videos") }
        val invokeIndex = requests.indexOfFirst { it.second.contains("/functions/v1/process-video") }
        (patchIndex >= 0).shouldBeTrue()
        (patchIndex < invokeIndex).shouldBeTrue()
        patchBody shouldContain "\"status\":\"uploaded\""
        patchBody shouldContain "\"error\":null"
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
        // RestException.message is a multi-line Code/Hint/URL/Headers dump —
        // these strings reach dialogs, so they must stay a single short line.
        (error?.message ?: "\n") shouldNotContain "\n"
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
    fun observeProcessing_survives_a_minutes_long_network_gap() = runTest {
        // Processing spans minutes and phones drop the network for a while
        // (WiFi<->cellular handoff, screen-off). A dozen consecutive failed
        // polls must not kill the analysis UI while the server keeps working.
        var call = 0
        val client = TestSupabase.client {
            call++
            when {
                call <= 12 -> respondError(HttpStatusCode.ServiceUnavailable, "blip")
                else       -> jsonResponse("""[{"status":"phase1_complete","progress":100.0,"error":null}]""")
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
    fun observeProcessing_network_errors_surface_friendly_text_not_raw_exception() = runTest {
        // Transport-level failures carry messages like "HTTP request to
        // https://... (GET) failed with message: null" — useless in a dialog.
        val client = TestSupabase.client { throw IOException() }
        val repo = VideosRepositoryImpl(client)
        repo.observeProcessing("vid-1", pollIntervalMs = 1).test {
            val last = awaitItem()
            last.status shouldBe "failed_connection"
            last.error shouldBe "Lost connection while checking progress"
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
            (last.error ?: "") shouldContain "HTTP 503"
            (last.error ?: "\n") shouldNotContain "\n"
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

package com.badmintontracker.shared.repo

import app.cash.turbine.turbineScope
import com.badmintontracker.shared.testing.TestSupabase
import com.badmintontracker.shared.testing.jsonResponse
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ClipsRepositoryTest {

    private val twoClips = """
      [
        {"id":"c1","video_id":"v1","owner_id":"u1","rally_index":0,"start_timestamp":0,"end_timestamp":1,
         "duration_seconds":1,"clip_storage_path":"uid/v1/c0.mp4","thumbnail_storage_path":null,
         "title":null,"annotation_count":0,"created_at":"2026-05-04T12:00:00Z"},
        {"id":"c2","video_id":"v1","owner_id":"u1","rally_index":1,"start_timestamp":2,"end_timestamp":3,
         "duration_seconds":1,"clip_storage_path":"uid/v1/c1.mp4","thumbnail_storage_path":null,
         "title":"nice","annotation_count":1,"created_at":"2026-05-04T12:01:00Z"}
      ]
    """.trimIndent()

    @Test
    fun listClips_orders_by_created_at_desc() = runTest {
        var capturedQuery: String? = null
        val client = TestSupabase.client { request ->
            capturedQuery = request.url.toString()
            jsonResponse(twoClips)
        }
        val repo = ClipsRepositoryImpl(client)

        val clips = repo.listClips()

        clips shouldHaveSize 2
        clips[0].id shouldBe "c1"
        capturedQuery!!.shouldContain("rally_clips")
        capturedQuery!!.shouldContain("order=created_at.desc")
    }

    @Test
    fun updateTitle_sends_patch_with_title_field() = runTest {
        var captured: Pair<String, String>? = null
        val client = TestSupabase.client { request ->
            val body = (request.body as? TextContent)?.text ?: ""
            captured = request.method.value to body
            jsonResponse("[]")
        }
        val repo = ClipsRepositoryImpl(client)

        val result = repo.updateTitle("c1", "renamed")

        result.isSuccess shouldBe true
        captured!!.first shouldBe "PATCH"
        captured!!.second.shouldContain(""""title":"renamed"""")
    }

    @Test
    fun countClipsForVideo_queries_server_filtered_by_video_id() = runTest {
        var capturedQuery: String? = null
        val client = TestSupabase.client { request ->
            capturedQuery = request.url.toString()
            jsonResponse("""[{"id":"c1"},{"id":"c2"}]""")
        }
        val repo = ClipsRepositoryImpl(client)

        repo.countClipsForVideo("v1").getOrNull() shouldBe 2

        capturedQuery!!.shouldContain("rally_clips")
        capturedQuery!!.shouldContain("video_id=eq.v1")
    }

    @Test
    fun observeClips_emits_initial_empty_then_refreshed_list() = runTest {
        val client = TestSupabase.client { _ -> jsonResponse(twoClips) }
        val repo = ClipsRepositoryImpl(client)

        turbineScope {
            val flow = repo.observeClips().testIn(backgroundScope)
            flow.awaitItem() shouldBe emptyList()
            repo.refresh()
            flow.awaitItem().map { it.id } shouldBe listOf("c1", "c2")
            flow.cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun pruneVideo_drops_cached_clips_for_that_video() = runTest {
        val client = TestSupabase.client { _ -> jsonResponse(twoClips) }
        val repo = ClipsRepositoryImpl(client)

        turbineScope {
            val flow = repo.observeClips().testIn(backgroundScope)
            flow.awaitItem() shouldBe emptyList()
            repo.refresh()
            flow.awaitItem() shouldHaveSize 2
            repo.pruneVideo("v1")
            flow.awaitItem() shouldBe emptyList()
            flow.cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * PostgREST as it actually answers a list: never more than [maxRows] at a
     * time whatever was asked for (the hosted default and supabase/config.toml
     * both say 1000), and the true total in Content-Range when asked to count.
     */
    private fun pagedServer(total: Int, maxRows: Int, withCount: Boolean = true) =
        TestSupabase.client { request ->
            val offset = request.url.parameters["offset"]?.toInt() ?: 0
            val limit = minOf(request.url.parameters["limit"]?.toInt() ?: maxRows, maxRows)
            val page = (offset until minOf(offset + limit, total)).joinToString(",") { i ->
                """{"id":"c$i","video_id":"v${i / 80}","owner_id":"u1","rally_index":${i % 80 + 1},
                   "start_timestamp":0,"end_timestamp":1,"duration_seconds":1,
                   "clip_storage_path":"uid/v/c$i.mp4","thumbnail_storage_path":null,
                   "title":null,"annotation_count":0,"created_at":"2026-05-04T12:00:00Z"}"""
            }
            val last = minOf(offset + limit, total) - 1
            respond(
                content = ByteReadChannel("[$page]"),
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                    HttpHeaders.ContentRange to listOf(
                        if (withCount) "$offset-$last/$total" else "$offset-$last/*",
                    ),
                ),
            )
        }

    @Test
    fun a_coach_with_more_clips_than_one_response_holds_still_gets_all_of_them() = runTest {
        // Roughly thirty matches. One unranged select came back with the first
        // thousand rows and no error, so the oldest matches lost rallies or
        // vanished from the list outright.
        val clips = ClipsRepositoryImpl(pagedServer(total = 2_500, maxRows = 1_000)).listClips()
        clips shouldHaveSize 2_500
        clips.map { it.id }.toSet() shouldHaveSize 2_500
    }

    @Test
    fun a_server_that_answers_in_smaller_pages_than_asked_for_loses_nothing() = runTest {
        // max_rows is server configuration. Stopping at the first page shorter
        // than the one requested would quietly bring the cap back.
        ClipsRepositoryImpl(pagedServer(total = 1_050, maxRows = 300)).listClips() shouldHaveSize 1_050
    }

    @Test
    fun a_server_that_will_not_count_is_paged_until_it_runs_dry() = runTest {
        ClipsRepositoryImpl(pagedServer(total = 2_100, maxRows = 1_000, withCount = false))
            .listClips() shouldHaveSize 2_100
    }

    @Test
    fun pages_are_cut_from_a_total_order() = runTest {
        // Clips cut by one run share a created_at. Paging over an order with
        // ties lets a row appear on two pages and another on none.
        var query: String? = null
        val client = TestSupabase.client { request ->
            query = request.url.toString()
            jsonResponse("[]")
        }
        ClipsRepositoryImpl(client).listClips()
        query!!.shouldContain("order=created_at.desc.nullslast%2Cid.asc.nullslast")
    }
}

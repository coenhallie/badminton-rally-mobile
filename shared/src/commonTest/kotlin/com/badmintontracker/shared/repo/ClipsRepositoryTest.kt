package com.badmintontracker.shared.repo

import app.cash.turbine.turbineScope
import com.badmintontracker.shared.testing.TestSupabase
import com.badmintontracker.shared.testing.jsonResponse
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.content.TextContent
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
}

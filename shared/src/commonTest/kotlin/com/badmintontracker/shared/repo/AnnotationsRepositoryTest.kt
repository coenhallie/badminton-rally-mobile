package com.badmintontracker.shared.repo

import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.testing.TestSupabase
import com.badmintontracker.shared.testing.jsonResponse
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test

class AnnotationsRepositoryTest {

    private val twoAnnotations = """
      [
        {"id":"a1","clip_id":"c1","timestamp_seconds":1.5,"body":"first",
         "created_at":"2026-05-04T12:00:00Z"},
        {"id":"a2","clip_id":"c1","timestamp_seconds":3.0,"body":"second",
         "created_at":"2026-05-04T12:00:01Z"}
      ]
    """.trimIndent()

    private val oneAnnotation = """
      [
        {"id":"a3","clip_id":"c9","timestamp_seconds":2.0,"body":"third",
         "created_at":"2026-05-04T12:00:02Z"}
      ]
    """.trimIndent()

    @Test
    fun listForClips_filters_by_an_in_list() = runTest {
        var capturedUrl: String? = null
        val client = TestSupabase.client { request ->
            capturedUrl = request.url.toString()
            jsonResponse(twoAnnotations)
        }
        val repo = AnnotationsRepositoryImpl(client)

        val items = repo.listForClips(listOf("c1", "c2")).getOrThrow()

        items shouldHaveSize 2
        capturedUrl!!.shouldContain("rally_annotations")
        // The operator and the ids, not the exact encoding: postgrest
        // percent-encodes the parentheses and comma, and that is not this
        // test's contract.
        capturedUrl!!.shouldContain("clip_id=in.")
        capturedUrl!!.shouldContain("c1")
        capturedUrl!!.shouldContain("c2")
    }

    @Test
    fun listForClips_issues_no_request_for_an_empty_list() = runTest {
        var requests = 0
        val client = TestSupabase.client {
            requests++
            jsonResponse("[]")
        }
        val repo = AnnotationsRepositoryImpl(client)

        repo.listForClips(emptyList()).getOrThrow().shouldBeEmpty()

        requests shouldBe 0
    }

    @Test
    fun listForClips_chunks_past_a_hundred_ids_and_merges_the_results() = runTest {
        var requests = 0
        val client = TestSupabase.client {
            requests++
            jsonResponse(if (requests == 1) twoAnnotations else oneAnnotation)
        }
        val repo = AnnotationsRepositoryImpl(client)

        val items = repo.listForClips((1..150).map { "c$it" }).getOrThrow()

        requests shouldBe 2
        items shouldHaveSize 3
    }

    @Test
    fun list_filters_by_clipId_and_orders_by_timestamp() = runTest {
        var capturedUrl: String? = null
        val client = TestSupabase.client { request ->
            capturedUrl = request.url.toString()
            jsonResponse(twoAnnotations)
        }
        val repo = AnnotationsRepositoryImpl(client)

        val items = repo.list("c1")

        items shouldHaveSize 2
        items[0].body shouldBe "first"
        capturedUrl!!.shouldContain("rally_annotations")
        capturedUrl!!.shouldContain("clip_id=eq.c1")
        capturedUrl!!.shouldContain("order=timestamp_seconds.asc")
    }

    @Test
    fun add_posts_to_rally_annotations() = runTest {
        var captured: Pair<String, String>? = null
        val client = TestSupabase.client { request ->
            val body = (request.body as? TextContent)?.text ?: ""
            captured = request.method.value to body
            jsonResponse(
                """[{"id":"a1","clip_id":"c1","timestamp_seconds":1.5,"body":"hi","created_at":"2026-05-04T12:00:00Z"}]""",
                HttpStatusCode.Created,
            )
        }
        val repo = AnnotationsRepositoryImpl(client)

        val result = repo.add("c1", 1.5f, "hi", null)

        result.isSuccess shouldBe true
        result.getOrThrow().body shouldBe "hi"
        captured!!.first shouldBe "POST"
        captured!!.second.shouldContain(""""body":"hi"""")
    }

    @Test
    fun add_snapshots_the_label_name_and_colour_into_the_post_body() = runTest {
        var captured: String? = null
        val client = TestSupabase.client { request ->
            captured = (request.body as? TextContent)?.text ?: ""
            jsonResponse(
                """[{"id":"a1","clip_id":"c1","timestamp_seconds":1.5,"body":"",
                     "label_name":"Net kill","label_color":"teal",
                     "created_at":"2026-08-24T12:00:00Z"}]""",
                HttpStatusCode.Created,
            )
        }
        val repo = AnnotationsRepositoryImpl(client)
        val label = AnnotationLabel(
            id = "l4",
            name = "Net kill",
            colorKey = "teal",
            createdAt = Instant.parse("2026-08-24T12:00:00Z"),
        )

        val result = repo.add("c1", 1.5f, "", label)

        result.isSuccess shouldBe true
        result.getOrThrow().labelName shouldBe "Net kill"
        captured!!.shouldContain(""""label_name":"Net kill"""")
        captured!!.shouldContain(""""label_color":"teal"""")
    }

    @Test
    fun delete_sends_delete_for_id() = runTest {
        var capturedUrl: String? = null
        var capturedMethod: String? = null
        val client = TestSupabase.client { request ->
            capturedMethod = request.method.value
            capturedUrl = request.url.toString()
            jsonResponse("[]")
        }
        val repo = AnnotationsRepositoryImpl(client)

        val result = repo.delete("a1")

        result.isSuccess shouldBe true
        capturedMethod shouldBe "DELETE"
        capturedUrl!!.shouldContain("id=eq.a1")
    }
}

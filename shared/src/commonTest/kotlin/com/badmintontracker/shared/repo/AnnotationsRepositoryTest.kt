package com.badmintontracker.shared.repo

import com.badmintontracker.shared.testing.TestSupabase
import com.badmintontracker.shared.testing.jsonResponse
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
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

        val result = repo.add("c1", 1.5f, "hi")

        result.isSuccess shouldBe true
        result.getOrThrow().body shouldBe "hi"
        captured!!.first shouldBe "POST"
        captured!!.second.shouldContain(""""body":"hi"""")
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

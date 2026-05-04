package com.badmintontracker.shared.repo

import com.badmintontracker.shared.testing.TestSupabase
import com.badmintontracker.shared.testing.jsonResponse
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
}

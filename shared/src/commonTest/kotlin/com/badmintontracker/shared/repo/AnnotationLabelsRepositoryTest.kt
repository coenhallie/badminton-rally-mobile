package com.badmintontracker.shared.repo

import com.badmintontracker.shared.model.LabelColor
import com.badmintontracker.shared.testing.TestSupabase
import com.badmintontracker.shared.testing.jsonResponse
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class AnnotationLabelsRepositoryTest {

    private val seeded = """
      [
        {"id":"l1","name":"Good shot","color_key":"green","created_at":"2026-08-24T12:00:00Z"},
        {"id":"l2","name":"Forced error","color_key":"amber","created_at":"2026-08-24T12:00:01Z"},
        {"id":"l3","name":"Unforced error","color_key":"red","created_at":"2026-08-24T12:00:02Z"}
      ]
    """.trimIndent()

    @Test
    fun refresh_loads_labels_in_creation_order_and_publishes_them() = runTest {
        var capturedUrl: String? = null
        val client = TestSupabase.client { request ->
            capturedUrl = request.url.toString()
            jsonResponse(seeded)
        }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())

        repo.refresh().isSuccess shouldBe true

        repo.labels.value shouldHaveSize 3
        repo.labels.value[0].name shouldBe "Good shot"
        capturedUrl!!.shouldContain("annotation_labels")
        capturedUrl!!.shouldContain("order=created_at.asc")
    }

    @Test
    fun refresh_caches_the_list_so_a_cold_start_offline_still_has_a_picker() = runTest {
        val settings = MapSettings()
        val client = TestSupabase.client { jsonResponse(seeded) }
        AnnotationLabelsRepositoryImpl(client, settings).refresh()

        val offline = TestSupabase.client { error("offline") }
        AnnotationLabelsRepositoryImpl(offline, settings).labels.value shouldHaveSize 3
    }

    @Test
    fun create_with_no_colour_takes_the_first_unused_swatch() = runTest {
        var posted: String? = null
        val client = TestSupabase.client { request ->
            val body = (request.body as? TextContent)?.text
            if (body == null) jsonResponse(seeded)
            else {
                posted = body
                jsonResponse(
                    """[{"id":"l4","name":"Net kill","color_key":"teal","created_at":"2026-08-24T12:00:03Z"}]""",
                    HttpStatusCode.Created,
                )
            }
        }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())
        repo.refresh()

        val created = repo.create("Net kill", null)

        created.isSuccess shouldBe true
        // green, amber and red are taken by the seeded three, so teal is next.
        posted!!.shouldContain(""""color_key":"teal"""")
        repo.labels.value shouldHaveSize 4
    }

    @Test
    fun create_trims_the_name_and_rejects_a_blank_one() = runTest {
        val client = TestSupabase.client { jsonResponse(seeded) }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())

        repo.create("   ", null).isFailure shouldBe true
    }

    @Test
    fun create_rejects_a_name_already_in_use_regardless_of_case() = runTest {
        val client = TestSupabase.client { jsonResponse(seeded) }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())
        repo.refresh()

        repo.create("good SHOT", null).isFailure shouldBe true
    }

    @Test
    fun delete_drops_the_label_from_the_published_list() = runTest {
        val client = TestSupabase.client { jsonResponse(seeded) }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())
        repo.refresh()

        repo.delete("l2").isSuccess shouldBe true

        repo.labels.value shouldHaveSize 2
        repo.labels.value.none { it.id == "l2" } shouldBe true
    }

    @Test
    fun recolor_updates_the_published_list() = runTest {
        val client = TestSupabase.client { jsonResponse(seeded) }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())
        repo.refresh()

        repo.recolor("l1", LabelColor.SLATE).isSuccess shouldBe true

        repo.labels.value.first { it.id == "l1" }.colorKey shouldBe "slate"
    }

    @Test
    fun nextUnusedColor_wraps_once_every_swatch_is_taken() {
        val taken = LabelColor.PALETTE.map { it.key }
        AnnotationLabelsRepositoryImpl.nextUnusedColor(taken) shouldBe LabelColor.PALETTE[0]
        AnnotationLabelsRepositoryImpl.nextUnusedColor(taken + "green") shouldBe LabelColor.PALETTE[1]
    }
}

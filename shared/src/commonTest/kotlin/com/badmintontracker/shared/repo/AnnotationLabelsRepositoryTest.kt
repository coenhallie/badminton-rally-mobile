package com.badmintontracker.shared.repo

import com.badmintontracker.shared.model.LabelColor
import com.badmintontracker.shared.testing.TestSupabase
import com.badmintontracker.shared.testing.jsonResponse
import com.russhwolf.settings.MapSettings
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
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

    // The shape Postgrest actually returns for a unique-violation: HTTP 409 with a
    // JSON body of {code, message}. supabase-kt decodes this into a
    // PostgrestRestException whose message is "<message>\n    Code:\n    <code>\n...",
    // so it carries both the postgres code 23505 and the constraint name.
    private val uniqueViolation = """
        {"code":"23505","message":"duplicate key value violates unique constraint \"annotation_labels_owner_name_key\""}
    """.trimIndent()

    @Test
    fun create_maps_a_server_side_duplicate_name_rejection_to_the_same_sentence() = runTest {
        // No refresh(): the in-memory list validate() checks is empty, so the
        // in-memory duplicate check genuinely passes and the request reaches the
        // server. This is the path a cold-start-offline or not-yet-refreshed user
        // hits, and it is asDuplicateName, not validate(), that must turn the
        // server's rejection into a readable sentence.
        val client = TestSupabase.client { _ ->
            respondError(
                status = HttpStatusCode.Conflict,
                content = uniqueViolation,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())

        val result = repo.create("Net kill", null)

        result.isFailure shouldBe true
        result.exceptionOrNull()?.message shouldBe "You already have a label called \"Net kill\"."
    }

    @Test
    fun rename_maps_a_server_side_duplicate_name_rejection_to_the_same_sentence() = runTest {
        val client = TestSupabase.client { _ ->
            respondError(
                status = HttpStatusCode.Conflict,
                content = uniqueViolation,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val repo = AnnotationLabelsRepositoryImpl(client, MapSettings())

        val result = repo.rename("l1", "Good shot")

        result.isFailure shouldBe true
        result.exceptionOrNull()?.message shouldBe "You already have a label called \"Good shot\"."
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

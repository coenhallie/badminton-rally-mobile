package com.badmintontracker.shared.model

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlin.test.Test

class RallyAnnotationSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodes_a_row_carrying_a_label_snapshot() {
        val row = json.decodeFromString<RallyAnnotation>(
            """{"id":"a1","clip_id":"c1","timestamp_seconds":1.5,"body":"",
                "label_name":"Net kill","label_color":"teal",
                "created_at":"2026-08-24T12:00:00Z"}"""
        )

        row.labelName shouldBe "Net kill"
        row.color shouldBe LabelColor.TEAL
    }

    @Test
    fun decodes_a_plain_text_row_with_no_label() {
        val row = json.decodeFromString<RallyAnnotation>(
            """{"id":"a2","clip_id":"c1","timestamp_seconds":3.0,"body":"good length",
                "created_at":"2026-08-24T12:00:01Z"}"""
        )

        row.body shouldBe "good length"
        row.labelName.shouldBeNull()
        row.color.shouldBeNull()
    }

    @Test
    fun a_snapshot_naming_an_unknown_swatch_still_decodes() {
        val row = json.decodeFromString<RallyAnnotation>(
            """{"id":"a3","clip_id":"c1","timestamp_seconds":4.0,"body":"",
                "label_name":"Drive","label_color":"chartreuse",
                "created_at":"2026-08-24T12:00:02Z"}"""
        )

        row.labelName shouldBe "Drive"
        row.color.shouldBeNull()
    }
}

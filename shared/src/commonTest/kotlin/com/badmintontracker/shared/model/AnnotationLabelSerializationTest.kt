package com.badmintontracker.shared.model

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlin.test.Test

class AnnotationLabelSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodes_a_row_and_resolves_its_colour() {
        val label = json.decodeFromString<AnnotationLabel>(
            """{"id":"l1","name":"Net kill","color_key":"teal",
                "created_at":"2026-08-24T12:00:00Z"}"""
        )

        label.name shouldBe "Net kill"
        label.colorKey shouldBe "teal"
        label.color shouldBe LabelColor.TEAL
    }

    @Test
    fun decodes_a_row_naming_a_swatch_this_build_does_not_know() {
        val label = json.decodeFromString<AnnotationLabel>(
            """{"id":"l2","name":"Drive","color_key":"chartreuse",
                "created_at":"2026-08-24T12:00:00Z"}"""
        )

        label.name shouldBe "Drive"
        label.color.shouldBeNull()
    }
}

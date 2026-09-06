package com.badmintontracker.shared.model

import io.kotest.matchers.collections.shouldHaveSize
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

    @Test
    fun a_row_with_no_usage_key_reads_as_both() {
        // The on-disk Settings cache written by the build before this column
        // existed. Without the default this would fail to decode on the first
        // launch after upgrade, and the offline picker would come up empty.
        val label = json.decodeFromString<AnnotationLabel>(
            """{"id":"l3","name":"Net kill","color_key":"teal",
                "created_at":"2026-08-24T12:00:00Z"}"""
        )

        label.scope shouldBe LabelUsage.BOTH
    }

    @Test
    fun usage_round_trips() {
        val label = json.decodeFromString<AnnotationLabel>(
            """{"id":"l4","name":"Serve","color_key":"blue",
                "created_at":"2026-08-24T12:00:00Z","usage":"scoreboard"}"""
        )

        label.scope shouldBe LabelUsage.SCOREBOARD
        json.encodeToString(AnnotationLabel.serializer(), label)
            .contains("\"usage\":\"scoreboard\"") shouldBe true
    }

    @Test
    fun an_unknown_usage_reads_as_both_and_the_list_still_decodes() {
        // The reason `usage` is a raw string and not a @Serializable enum: the
        // palette is fetched with decodeList, so one row written by a newer
        // build must not take down the other nine.
        val labels = json.decodeFromString<List<AnnotationLabel>>(
            """[{"id":"l5","name":"Drive","color_key":"red",
                 "created_at":"2026-08-24T12:00:00Z","usage":"courtside"},
                {"id":"l6","name":"Lift","color_key":"green",
                 "created_at":"2026-08-24T12:00:00Z","usage":"clips"}]"""
        )

        labels shouldHaveSize 2
        labels[0].scope shouldBe LabelUsage.BOTH
        labels[1].scope shouldBe LabelUsage.CLIPS
    }
}

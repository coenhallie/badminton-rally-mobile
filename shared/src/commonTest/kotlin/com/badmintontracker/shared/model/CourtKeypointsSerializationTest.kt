package com.badmintontracker.shared.model

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlin.test.Test

class CourtKeypointsSerializationTest {

    private val json = Json

    @Test
    fun serializes_with_exact_desktop_field_names() {
        val kp = CourtKeypoints(
            topLeft = listOf(1f, 2f), topRight = listOf(3f, 4f),
            bottomRight = listOf(5f, 6f), bottomLeft = listOf(7f, 8f),
            netLeft = listOf(9f, 10f), netRight = listOf(11f, 12f),
            serviceLineNearLeft = listOf(13f, 14f), serviceLineNearRight = listOf(15f, 16f),
            serviceLineFarLeft = listOf(17f, 18f), serviceLineFarRight = listOf(19f, 20f),
            centerNear = listOf(21f, 22f), centerFar = listOf(23f, 24f),
        )
        val encoded = json.encodeToString(CourtKeypoints.serializer(), kp)
        // Field-name parity with desktop CourtSetup.vue saveAndProceed().
        encoded shouldBe """{"top_left":[1.0,2.0],"top_right":[3.0,4.0],""" +
            """"bottom_right":[5.0,6.0],"bottom_left":[7.0,8.0],""" +
            """"net_left":[9.0,10.0],"net_right":[11.0,12.0],""" +
            """"service_line_near_left":[13.0,14.0],"service_line_near_right":[15.0,16.0],""" +
            """"service_line_far_left":[17.0,18.0],"service_line_far_right":[19.0,20.0],""" +
            """"center_near":[21.0,22.0],"center_far":[23.0,24.0]}"""
    }

    @Test
    fun round_trips() {
        val kp = CourtKeypoints(
            topLeft = listOf(100.5f, 200.25f), topRight = listOf(3f, 4f),
            bottomRight = listOf(5f, 6f), bottomLeft = listOf(7f, 8f),
            netLeft = listOf(9f, 10f), netRight = listOf(11f, 12f),
            serviceLineNearLeft = listOf(13f, 14f), serviceLineNearRight = listOf(15f, 16f),
            serviceLineFarLeft = listOf(17f, 18f), serviceLineFarRight = listOf(19f, 20f),
            centerNear = listOf(21f, 22f), centerFar = listOf(23f, 24f),
        )
        val decoded = json.decodeFromString(
            CourtKeypoints.serializer(),
            json.encodeToString(CourtKeypoints.serializer(), kp),
        )
        decoded shouldBe kp
    }

    private val twelve = """"top_left":[1.0,2.0],"top_right":[3.0,4.0],""" +
        """"bottom_right":[5.0,6.0],"bottom_left":[7.0,8.0],""" +
        """"net_left":[9.0,10.0],"net_right":[11.0,12.0],""" +
        """"service_line_near_left":[13.0,14.0],"service_line_near_right":[15.0,16.0],""" +
        """"service_line_far_left":[17.0,18.0],"service_line_far_right":[19.0,20.0],""" +
        """"center_near":[21.0,22.0],"center_far":[23.0,24.0]"""

    private fun marks(markedAtSeconds: Double?) = CourtKeypoints(
        topLeft = listOf(1f, 2f), topRight = listOf(3f, 4f),
        bottomRight = listOf(5f, 6f), bottomLeft = listOf(7f, 8f),
        netLeft = listOf(9f, 10f), netRight = listOf(11f, 12f),
        serviceLineNearLeft = listOf(13f, 14f), serviceLineNearRight = listOf(15f, 16f),
        serviceLineFarLeft = listOf(17f, 18f), serviceLineFarRight = listOf(19f, 20f),
        centerNear = listOf(21f, 22f), centerFar = listOf(23f, 24f),
        markedAtSeconds = markedAtSeconds,
    )

    @Test
    fun marks_written_without_a_time_read_as_having_none() {
        // What the web app writes, and what every mark saved before
        // 2026-09-27 is.
        json.decodeFromString(CourtKeypoints.serializer(), "{$twelve}").markedAtSeconds shouldBe null
    }

    @Test
    fun the_marking_time_reads_back_from_marked_at_s() {
        json.decodeFromString(CourtKeypoints.serializer(), """{$twelve,"marked_at_s":1.0}""")
            .markedAtSeconds shouldBe 1.0
    }

    @Test
    fun marks_without_a_time_write_no_marked_at_s_key() {
        // Default Json, as the Postgrest client encodes the row patch: the
        // column keeps exactly the twelve keys the web app writes.
        json.encodeToString(CourtKeypoints.serializer(), marks(markedAtSeconds = null)) shouldBe "{$twelve}"
    }

    @Test
    fun the_marking_time_is_written_as_marked_at_s() {
        json.encodeToString(CourtKeypoints.serializer(), marks(markedAtSeconds = 0.1)) shouldBe
            """{$twelve,"marked_at_s":0.1}"""
    }
}

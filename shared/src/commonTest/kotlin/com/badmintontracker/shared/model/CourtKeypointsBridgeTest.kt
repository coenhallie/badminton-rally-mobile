package com.badmintontracker.shared.model

import com.badmintontracker.analysis.geometry.Point
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class CourtKeypointsBridgeTest {

    /** Every field gets a distinct value so a transposition cannot pass. */
    private val wire = CourtKeypoints(
        topLeft = listOf(1f, 2f),
        topRight = listOf(3f, 4f),
        bottomRight = listOf(5f, 6f),
        bottomLeft = listOf(7f, 8f),
        netLeft = listOf(9f, 10f),
        netRight = listOf(11f, 12f),
        serviceLineNearLeft = listOf(13f, 14f),
        serviceLineNearRight = listOf(15f, 16f),
        serviceLineFarLeft = listOf(17f, 18f),
        serviceLineFarRight = listOf(19f, 20f),
        centerNear = listOf(21f, 22f),
        centerFar = listOf(23f, 24f),
    )

    @Test
    fun every_field_converts_to_the_matching_field() {
        val a = wire.toAnalysis()
        // Asserted field by field rather than as a list. The twelve points are
        // positionally meaningful downstream - COURT_KEYPOINT_POSITIONS pairs
        // them by index - so a swapped pair still yields twelve valid
        // correspondences and a homography that maps the court wrong.
        a.topLeft shouldBe Point(1.0, 2.0)
        a.topRight shouldBe Point(3.0, 4.0)
        a.bottomRight shouldBe Point(5.0, 6.0)
        a.bottomLeft shouldBe Point(7.0, 8.0)
        a.netLeft shouldBe Point(9.0, 10.0)
        a.netRight shouldBe Point(11.0, 12.0)
        a.serviceLineNearLeft shouldBe Point(13.0, 14.0)
        a.serviceLineNearRight shouldBe Point(15.0, 16.0)
        a.serviceLineFarLeft shouldBe Point(17.0, 18.0)
        a.serviceLineFarRight shouldBe Point(19.0, 20.0)
        a.centerNear shouldBe Point(21.0, 22.0)
        a.centerFar shouldBe Point(23.0, 24.0)
    }

    @Test
    fun a_fractional_coordinate_widens_without_corruption() {
        // Float to Double widening is exact for a value like 960.5, and a
        // value that is NOT exactly representable would surface a lossy
        // conversion route (via toString, say) as an inexact result.
        val kp = wire.copy(topLeft = listOf(960.5f, 540.25f)).toAnalysis()
        kp.topLeft shouldBe Point(960.5, 540.25)
    }

    @Test
    fun the_conversion_is_total() {
        // fromMap returns null when a key is missing, because it parses
        // untrusted JSON. The wire type's twelve fields are non-nullable, so
        // this conversion cannot fail and must not force a !! on callers.
        val result: com.badmintontracker.analysis.geometry.CourtKeypoints = wire.toAnalysis()
        result.corners.size shouldBe 4
    }
}

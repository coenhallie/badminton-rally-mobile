package com.badmintontracker.android.ui.theme

import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The shape scale, pinned so it cannot drift from iosApp's ShuttlRadiusTests.
 * Two clients rendering the same design at different radii is the exact kind of
 * divergence nobody notices until the screenshots sit side by side.
 */
class ShuttlShapesTest {
    @Test
    fun radius_scale_matches_the_design() {
        ShuttlRadius.extraSmall shouldBe 8.dp
        ShuttlRadius.small shouldBe 12.dp
        ShuttlRadius.medium shouldBe 16.dp
        ShuttlRadius.large shouldBe 20.dp
        ShuttlRadius.extraLarge shouldBe 28.dp
        ShuttlRadius.pill shouldBe 999.dp
    }

    @Test
    fun material_shapes_are_no_longer_square() {
        // Guards the reversal itself: this scale replaced an all-0dp one, and a
        // revert would silently un-round every card in the app. Covers all five
        // M3 slots, not just two - the other three could regress to 0.dp with
        // neither this test nor radius_scale_matches_the_design noticing.
        ShuttlShapes.extraSmall shouldBe androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
        ShuttlShapes.small shouldBe androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
        ShuttlShapes.medium shouldBe androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
        ShuttlShapes.large shouldBe androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
        ShuttlShapes.extraLarge shouldBe androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
    }
}

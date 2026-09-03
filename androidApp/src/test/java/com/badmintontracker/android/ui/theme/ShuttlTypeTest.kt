package com.badmintontracker.android.ui.theme

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The mirror of iosApp's ShuttlTypeTests. Asserts the scale table rather than
 * the built TextStyles, which need a font resource this source set cannot load.
 */
class ShuttlTypeTest {
    @Test
    fun scale_matches_the_design() {
        ShuttlScale.display.sizeSp shouldBe 40f
        ShuttlScale.display.trackingEm shouldBe -0.035f
        ShuttlScale.headlineLarge.sizeSp shouldBe 28f
        ShuttlScale.headlineMedium.sizeSp shouldBe 22f
        ShuttlScale.statNumber.sizeSp shouldBe 26f
        ShuttlScale.titleLarge.sizeSp shouldBe 16f
        ShuttlScale.titleMedium.sizeSp shouldBe 15f
        ShuttlScale.bodyLarge.sizeSp shouldBe 16f
        ShuttlScale.bodyMedium.sizeSp shouldBe 14f
        ShuttlScale.bodySmall.sizeSp shouldBe 12f
        ShuttlScale.labelSmall.sizeSp shouldBe 11f
        ShuttlScale.labelSmall.trackingEm shouldBe 0.05f
    }

    @Test
    fun tracking_converts_to_sp() {
        // -0.035em at 40sp is -1.4sp. Same arithmetic as iOS's kerning, and the
        // same thing that gets got wrong when the scale is edited.
        ShuttlScale.display.trackingSp shouldBe (-1.4f plusOrMinus 0.001f)
        ShuttlScale.labelSmall.trackingSp shouldBe (0.55f plusOrMinus 0.001f)
    }
}

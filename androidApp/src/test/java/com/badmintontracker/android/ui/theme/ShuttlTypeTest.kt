package com.badmintontracker.android.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The mirror of iosApp's ShuttlTypeTests. Asserts the scale table rather than
 * the built TextStyles, which need a font resource this source set cannot load.
 */
class ShuttlTypeTest {
    private data class Expected(
        val name: String,
        val role: ShuttlScale.Role,
        val sizeSp: Float,
        val weight: FontWeight,
        val trackingEm: Float,
        val lineHeightMultiple: Float,
    )

    // Every number, for every role. This is what makes the two platforms'
    // scale tables checkable against each other role by role - the reason the
    // scale was split out of the built styles in the first place.
    private val expectations = listOf(
        Expected("display", ShuttlScale.display, 40f, FontWeight.Medium, -0.035f, 1.08f),
        Expected("headlineLarge", ShuttlScale.headlineLarge, 28f, FontWeight.Medium, -0.030f, 1.15f),
        Expected("headlineMedium", ShuttlScale.headlineMedium, 22f, FontWeight.Medium, -0.020f, 1.20f),
        Expected("statNumber", ShuttlScale.statNumber, 26f, FontWeight.Medium, -0.030f, 1.15f),
        Expected("wordmark", ShuttlScale.wordmark, 24f, FontWeight.Bold, -0.010f, 1.20f),
        Expected("titleLarge", ShuttlScale.titleLarge, 16f, FontWeight.SemiBold, -0.010f, 1.30f),
        Expected("titleMedium", ShuttlScale.titleMedium, 15f, FontWeight.SemiBold, -0.010f, 1.30f),
        Expected("labelMedium", ShuttlScale.labelMedium, 13f, FontWeight.SemiBold, -0.010f, 1.30f),
        Expected("bodyLarge", ShuttlScale.bodyLarge, 16f, FontWeight.Normal, 0f, 1.45f),
        Expected("bodyMedium", ShuttlScale.bodyMedium, 14f, FontWeight.Normal, 0f, 1.45f),
        Expected("bodySmall", ShuttlScale.bodySmall, 12f, FontWeight.Normal, 0f, 1.40f),
        Expected("labelSmall", ShuttlScale.labelSmall, 11f, FontWeight.Medium, 0.050f, 1.30f),
    )

    @Test
    fun scale_matches_the_design() {
        for (e in expectations) {
            withClue(e.name) {
                e.role.sizeSp shouldBe e.sizeSp
                e.role.weight shouldBe e.weight
                e.role.trackingEm shouldBe e.trackingEm
            }
        }
    }

    @Test
    fun line_heights_match_ios() {
        // Mirror of iosApp's testLineHeightsMatchAndroid. Stored on both
        // platforms, so it is asserted on both.
        for (e in expectations) {
            withClue(e.name) {
                e.role.lineHeightMultiple shouldBe e.lineHeightMultiple
            }
        }
    }

    @Test
    fun tracking_converts_to_sp() {
        // -0.035em at 40sp is -1.4sp. Same arithmetic as iOS's kerning, and the
        // same thing that gets got wrong when the scale is edited.
        ShuttlScale.display.trackingSp shouldBe (-1.4f plusOrMinus 0.001f)
        ShuttlScale.labelSmall.trackingSp shouldBe (0.55f plusOrMinus 0.001f)
    }

    @Test
    fun every_material_slot_is_archivo() {
        // Guards against the exact regression this test was added for: before
        // this task ShuttlTypography set every slot it populated to the system
        // font, so the app was consistently on one typeface. Populating only
        // some of M3's fifteen slots with Archivo and leaving the rest on the
        // default would put two typefaces on the same screen.
        val slots = listOf(
            "displayLarge" to ShuttlTypography.displayLarge,
            "displayMedium" to ShuttlTypography.displayMedium,
            "displaySmall" to ShuttlTypography.displaySmall,
            "headlineLarge" to ShuttlTypography.headlineLarge,
            "headlineMedium" to ShuttlTypography.headlineMedium,
            "headlineSmall" to ShuttlTypography.headlineSmall,
            "titleLarge" to ShuttlTypography.titleLarge,
            "titleMedium" to ShuttlTypography.titleMedium,
            "titleSmall" to ShuttlTypography.titleSmall,
            "bodyLarge" to ShuttlTypography.bodyLarge,
            "bodyMedium" to ShuttlTypography.bodyMedium,
            "bodySmall" to ShuttlTypography.bodySmall,
            "labelLarge" to ShuttlTypography.labelLarge,
            "labelMedium" to ShuttlTypography.labelMedium,
            "labelSmall" to ShuttlTypography.labelSmall,
        )
        for ((name, style) in slots) {
            withClue(name) {
                style.fontFamily shouldBe Archivo
            }
        }
    }

    private data class DesignSlot(
        val name: String,
        val built: TextStyle,
        val scale: ShuttlScale.Role,
    )

    // Pairs each M3 slot the design scale owns with the ShuttlScale row it is
    // supposed to be wired to. One line per slot, so a new design slot is one
    // line to add.
    private val designSlots = listOf(
        DesignSlot("headlineLarge", ShuttlTypography.headlineLarge, ShuttlScale.headlineLarge),
        DesignSlot("headlineMedium", ShuttlTypography.headlineMedium, ShuttlScale.headlineMedium),
        DesignSlot("titleLarge", ShuttlTypography.titleLarge, ShuttlScale.titleLarge),
        DesignSlot("titleMedium", ShuttlTypography.titleMedium, ShuttlScale.titleMedium),
        DesignSlot("bodyLarge", ShuttlTypography.bodyLarge, ShuttlScale.bodyLarge),
        DesignSlot("bodyMedium", ShuttlTypography.bodyMedium, ShuttlScale.bodyMedium),
        DesignSlot("bodySmall", ShuttlTypography.bodySmall, ShuttlScale.bodySmall),
        DesignSlot("labelSmall", ShuttlTypography.labelSmall, ShuttlScale.labelSmall),
    )

    @Test
    fun design_slots_carry_their_scale_rows_metrics() {
        // every_material_slot_is_archivo only checks fontFamily, so it would
        // not catch a design slot wired to the wrong ShuttlScale row, or one
        // quietly reverted to Default.<slot>.archivo() (which is also
        // Archivo, just at M3's own size). This checks the wiring itself: the
        // built TextStyle for each design-owned slot must carry the size,
        // weight, line height and tracking its own ShuttlScale row specifies.
        //
        // Deliberately excludes the seven undesigned slots (displayLarge/
        // Medium/Small, headlineSmall, titleSmall, labelLarge, labelMedium):
        // those keep M3's own metrics on purpose, and pinning their numbers
        // here would freeze values this task does not own and did not choose.
        for (slot in designSlots) {
            withClue(slot.name) {
                slot.built.fontSize shouldBe slot.scale.sizeSp.sp
                slot.built.fontWeight shouldBe slot.scale.weight
                slot.built.lineHeight shouldBe (slot.scale.sizeSp * slot.scale.lineHeightMultiple).sp
                slot.built.letterSpacing shouldBe slot.scale.trackingSp.sp
            }
        }
    }
}

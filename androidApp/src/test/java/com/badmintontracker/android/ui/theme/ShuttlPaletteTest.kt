package com.badmintontracker.android.ui.theme

import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.pow
import kotlin.test.Test

/**
 * The palette's accessibility rules, asserted rather than trusted.
 *
 * The mirror of iosApp's ShuttlPaletteTests.swift. Reads raw Longs rather than
 * Compose Colors on purpose: this source set has no Robolectric, so anything
 * needing an Android runtime could not run here.
 */
class ShuttlPaletteTest {

    private fun channel(c: Long): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(rgb: Long): Double =
        0.2126 * channel((rgb shr 16) and 0xFF) +
        0.7152 * channel((rgb shr 8) and 0xFF) +
        0.0722 * channel(rgb and 0xFF)

    private fun contrast(a: Long, b: Long): Double {
        val (la, lb) = luminance(a) to luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private val themes = listOf<Pair<String, (ShuttlPalette.Tone) -> Long>>(
        "light" to { it.light },
        "dark" to { it.dark },
    )

    @Test
    fun contrast_helper_matches_known_values() {
        contrast(0x000000, 0xFFFFFF).shouldBeGreaterThanOrEqual(20.99)
        contrast(0xFFFFFF, 0xFFFFFF).shouldBeLessThan(1.01)
    }

    @Test
    fun body_text_clears_aa_on_every_surface() {
        for ((theme, pick) in themes) {
            val backgrounds = listOf(
                "bg" to pick(ShuttlPalette.bg),
                "bgSecondary" to pick(ShuttlPalette.bgSecondary),
                "bgTertiary" to pick(ShuttlPalette.bgTertiary),
            )
            val foregrounds = listOf(
                "text" to pick(ShuttlPalette.text),
                "textHeading" to pick(ShuttlPalette.textHeading),
                "textSecondary" to pick(ShuttlPalette.textSecondary),
                "textTertiary" to pick(ShuttlPalette.textTertiary),
            )
            for ((fgName, fg) in foregrounds) {
                for ((bgName, bg) in backgrounds) {
                    withClue("$theme $fgName on $bgName") {
                        contrast(fg, bg).shouldBeGreaterThanOrEqual(4.5)
                    }
                }
            }
        }
    }

    @Test
    fun muted_text_clears_large_text_threshold_only() {
        for ((theme, pick) in themes) {
            val ratio = contrast(pick(ShuttlPalette.textMuted), pick(ShuttlPalette.bg))
            withClue("$theme textMuted") {
                ratio.shouldBeGreaterThanOrEqual(3.0)
                // Below the body threshold by design. It is the hero line and
                // nothing else, which is why it is a separate token rather than
                // textTertiary used at two sizes.
                ratio.shouldBeLessThan(4.5)
            }
        }
    }

    @Test
    fun accent_carries_on_accent_text() {
        for ((theme, pick) in themes) {
            withClue("$theme onAccent on accent") {
                contrast(pick(ShuttlPalette.onAccent), pick(ShuttlPalette.accent))
                    .shouldBeGreaterThanOrEqual(4.5)
            }
        }
    }

    @Test
    fun accent_is_a_fill_colour_not_a_text_colour() {
        contrast(ShuttlPalette.accent.light, ShuttlPalette.bg.light).shouldBeLessThan(4.5)
        for ((theme, pick) in themes) {
            withClue("$theme accentDark as text") {
                contrast(pick(ShuttlPalette.accentDark), pick(ShuttlPalette.bg))
                    .shouldBeGreaterThanOrEqual(4.5)
            }
        }
    }

    @Test
    fun dark_palette_matches_the_mock() {
        ShuttlPalette.bg.dark shouldBe 0x0B0C0DL
        ShuttlPalette.accent.dark shouldBe 0x3EE27CL
        ShuttlPalette.onAccent.dark shouldBe 0x06210FL
        ShuttlPalette.textMuted.dark shouldBe 0x5D6462L
    }
}

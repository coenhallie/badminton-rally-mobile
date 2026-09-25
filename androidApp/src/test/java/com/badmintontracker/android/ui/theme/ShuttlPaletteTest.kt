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

            // This next assertion looks backwards - it asserts a FAILURE - but
            // that is the honest constraint. textMuted was only ever measured
            // against bg, its one intended consumer (phase 2's hero line). On
            // bgTertiary it does not clear this same 3.0:1 large-text floor
            // (2.920:1 light / 2.939:1 dark). Pinning that failure means if
            // someone later "improves" textMuted until this goes green, they are
            // forced to notice they have changed which surfaces it is valid on,
            // rather than silently gaining a surface no one verified. Text on a
            // raised surface must use textTertiary, not textMuted.
            val raisedRatio = contrast(pick(ShuttlPalette.textMuted), pick(ShuttlPalette.bgTertiary))
            withClue("$theme textMuted on bgTertiary now clears 3.0; textMuted may be safe on raised surfaces now, update the doc comment and this test to say so") {
                raisedRatio.shouldBeLessThan(3.0)
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
    fun error_carries_on_error_text() {
        for ((theme, pick) in themes) {
            withClue("$theme onError on error") {
                contrast(pick(ShuttlPalette.onError), pick(ShuttlPalette.error))
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

    /**
     * The match list's analysis glyphs are the only thing saying which analysis
     * a match has, so they are held to the 3:1 non-text floor on their chip, on
     * both row surfaces: the quiet row (bg) and the raised card (bgTertiary).
     * The accent itself only makes 2.4:1 there in light, which is why the
     * glyph is its own token.
     */
    @Test
    fun analysis_glyph_reads_on_its_chip_on_every_row_surface() {
        fun blend(fg: Long, bg: Long, alpha: Float): Long {
            fun mix(shift: Int): Long {
                val f = (fg shr shift) and 0xFF
                val b = (bg shr shift) and 0xFF
                return Math.round(alpha * f + (1 - alpha) * b).toLong() shl shift
            }
            return mix(16) or mix(8) or mix(0)
        }
        val alphas = mapOf("light" to ShuttlPalette.analysisChipOpacity.light, "dark" to ShuttlPalette.analysisChipOpacity.dark)
        for ((theme, pick) in themes) {
            for ((surfaceName, surface) in listOf("bg" to pick(ShuttlPalette.bg), "bgTertiary" to pick(ShuttlPalette.bgTertiary))) {
                val chip = blend(pick(ShuttlPalette.accent), surface, alphas.getValue(theme))
                withClue("$theme analysisGlyph on its chip on $surfaceName") {
                    contrast(pick(ShuttlPalette.analysisGlyph), chip).shouldBeGreaterThanOrEqual(3.0)
                }
            }
        }
    }

    /**
     * Menus and dialogs draw text, textHeading (onSurface/onBackground) and
     * textSecondary (onSurfaceVariant). textTertiary is left off on purpose:
     * it makes 4.34:1 on the dark value, and lifting bgRaised to clear it
     * would sink it back into the raised card it has to stand off.
     */
    @Test
    fun raised_surface_carries_the_text_menus_and_dialogs_draw() {
        for ((theme, pick) in themes) {
            for ((name, fg) in listOf(
                "text" to pick(ShuttlPalette.text),
                "textHeading" to pick(ShuttlPalette.textHeading),
                "textSecondary" to pick(ShuttlPalette.textSecondary),
            )) {
                withClue("$theme $name on bgRaised") {
                    contrast(fg, pick(ShuttlPalette.bgRaised)).shouldBeGreaterThanOrEqual(4.5)
                }
            }
        }
    }

    /**
     * A menu opened from a raised card must not merge into it, and in dark no
     * shadow does that job. At least one step lighter than the card.
     */
    @Test
    fun raised_surfaces_stand_off_the_raised_card_in_dark() {
        (luminance(ShuttlPalette.bgRaised.dark) > luminance(ShuttlPalette.bgTertiary.dark)) shouldBe true
    }

    @Test
    fun dark_palette_matches_the_mock() {
        ShuttlPalette.bg.dark shouldBe 0x0B0C0DL
        ShuttlPalette.accent.dark shouldBe 0x3EE27CL
        ShuttlPalette.onAccent.dark shouldBe 0x06210FL
        ShuttlPalette.textMuted.dark shouldBe 0x5D6462L
    }
}

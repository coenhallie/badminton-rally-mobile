package com.badmintontracker.android.ui.theme

/**
 * Every design token as a raw RGB integer, light and dark.
 *
 * Separate from the Compose ColorScheme on purpose: the unit test source set has
 * no Robolectric, so contrast rules could only be asserted against plain values.
 * Mirrors iosApp's ShuttlPalette.swift, which exists for the same reason and
 * carries the same token names.
 *
 * Dark is lifted from the badminton-design mock. Light is a derivation, not a
 * recolour: the mock's 0x3EE27C is 1.6:1 on white and unusable there.
 */
internal object ShuttlPalette {
    data class Tone(val light: Long, val dark: Long)

    val bg              = Tone(0xFFFFFF, 0x0B0C0D)
    val bgSecondary     = Tone(0xF5F7F6, 0x121415)
    val bgTertiary      = Tone(0xEDF0EE, 0x161819)
    val bgInput         = Tone(0xEDF0EE, 0x0E0F10)
    val border          = Tone(0xE3E6E4, 0x1A1D1E)
    val borderSecondary = Tone(0xCED3D0, 0x22262A)
    val textHeading     = Tone(0x0B0C0D, 0xF2F4F3)
    val text            = Tone(0x16191A, 0xF2F4F3)
    val textSecondary   = Tone(0x545C58, 0x8D938F)
    val textTertiary    = Tone(0x5F6763, 0x7F8682)

    /**
     * The hero line, and nothing else. Below the 4.5:1 body threshold by design;
     * see ShuttlPaletteTest.muted_text_clears_large_text_threshold_only.
     *
     * Verified only against [bg], its one intended consumer: 3.351:1 light /
     * 3.230:1 dark. On [bgSecondary] it is 3.115:1 light / 3.048:1 dark, and
     * on [bgTertiary] it drops to 2.920:1 light / 2.939:1 dark, below the
     * 3.0:1 large-text floor it otherwise clears. Do not put it on a raised
     * surface; use [textTertiary] there instead.
     */
    val textMuted       = Tone(0x878E8A, 0x5D6462)

    /** A fill colour. Accent-coloured TEXT uses [accentDark]. */
    val accent          = Tone(0x16A34A, 0x3EE27C)

    /**
     * What sits on top of an [accent] fill, in both themes. The mock's own
     * pattern: near-black on green, which is the pairing that survives the jump
     * from a dark-only design into a light theme.
     */
    val onAccent        = Tone(0x04240F, 0x06210F)
    val accentDark      = Tone(0x15803D, 0x22C55E)

    /**
     * The two sides of the scoreboard. Deliberately not the accent green and the
     * info blue: those are interface colours sized for a chip, and these are
     * full-bleed halves carrying white numerals, so they are picked for contrast
     * against white first and family resemblance second. Deeper in dark, where a
     * lit-up half at arm's length in a dim hall is the thing to avoid. Mirrors
     * iosApp's ShuttlTheme.sideHome / sideAway.
     */
    val sideHome        = Tone(0x15803D, 0x14532D)
    val sideAway        = Tone(0x1D4ED8, 0x1E3A8A)
    val error           = Tone(0xEF4444, 0xEF4444)

    /**
     * What sits on top of an [error] fill, in both themes. [error] itself is
     * theme-invariant, so one near-black value clears 4.5:1 in both: 5.075:1.
     * White does not - 3.763:1 - which is the bug this token exists to fix.
     */
    val onError         = Tone(0x200808, 0x200808)
    val warning         = Tone(0xF59E0B, 0xF59E0B)
    val info            = Tone(0x3B82F6, 0x3B82F6)
}

package com.badmintontracker.shared.signin

import com.badmintontracker.shared.prefs.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SignInCopyTest {
    private val all = listOf(
        SignInCopy.WORDMARK, SignInCopy.TITLE, SignInCopy.EMAIL, SignInCopy.PASSWORD,
        SignInCopy.SUBMIT, SignInCopy.SUBMITTING, SignInCopy.FOOTNOTE,
        SignInCopy.themeToggle(ThemeMode.LIGHT), SignInCopy.themeToggle(ThemeMode.DARK),
    )

    @Test fun no_em_dash_anywhere() {
        all.forEach { assertFalse('\u2014' in it, it) }
    }

    // The label says what a tap does, so it names the other theme.
    @Test fun the_theme_label_names_the_theme_a_tap_switches_to() {
        assertEquals("Switch to dark mode", SignInCopy.themeToggle(ThemeMode.LIGHT))
        assertEquals("Switch to light mode", SignInCopy.themeToggle(ThemeMode.DARK))
    }
}

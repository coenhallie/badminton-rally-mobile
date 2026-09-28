package com.badmintontracker.shared.signin

import com.badmintontracker.shared.prefs.ThemeMode

/**
 * The sign-in screen's words and the splash before it, for both clients, the
 * way HomeCopy holds the match list's. They used to be typed out once per
 * platform, which is how two screens that should read the same drift apart.
 *
 * The error messages are not here: they come from the server's answer, through
 * `friendlyAuthError` (auth/AuthErrorMessages.kt).
 */
object SignInCopy {
    /**
     * The splash's and the sign-in screen's title, in the title bar's slot.
     * The list's own title ("Matches") takes the same slot once signed in.
     */
    const val WORDMARK = "SHUTTL."

    /** The page heading under the title bar: what this screen is for. */
    const val TITLE = "Sign in"

    const val EMAIL = "Email"
    const val PASSWORD = "Password"
    const val SUBMIT = "Sign in"
    const val SUBMITTING = "Signing in…"

    const val FOOTNOTE = "Registration is closed. Contact the admin if you need an account."

    /**
     * The theme button's spoken label. Names what a tap does, not what is
     * showing: the glyph is the theme it switches to (a moon while light).
     */
    fun themeToggle(current: ThemeMode): String = when (current) {
        ThemeMode.LIGHT -> "Switch to dark mode"
        ThemeMode.DARK  -> "Switch to light mode"
    }
}

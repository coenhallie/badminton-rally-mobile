package com.badmintontracker.android.nav

import com.badmintontracker.shared.repo.AuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthNavigationTest {

    @Test
    fun a_restored_session_starts_on_home() {
        assertEquals(Route.Home, authStartDestination(AuthState.AUTHENTICATED))
    }

    @Test
    fun nobody_signed_in_starts_on_sign_in() {
        assertEquals(Route.SignIn, authStartDestination(AuthState.UNAUTHENTICATED))
    }

    @Test
    fun a_session_that_ends_anywhere_in_the_app_goes_to_sign_in() {
        assertEquals(AuthRedirect.TO_SIGN_IN, authRedirect(AuthState.UNAUTHENTICATED, onSignIn = false))
    }

    @Test
    fun a_session_that_arrives_while_on_sign_in_goes_home() {
        assertEquals(AuthRedirect.TO_HOME, authRedirect(AuthState.AUTHENTICATED, onSignIn = true))
    }

    @Test
    fun a_signed_in_coach_is_left_where_they_are() {
        // Including the one that matters: a token refresh that failed for want of
        // signal maps to AUTHENTICATED in shared code, so the scoring board a
        // coach is standing on is not replaced by the sign-in screen.
        assertNull(authRedirect(AuthState.AUTHENTICATED, onSignIn = false))
    }

    @Test
    fun sign_in_is_not_navigated_to_from_itself() {
        assertNull(authRedirect(AuthState.UNAUTHENTICATED, onSignIn = true))
    }

    @Test
    fun a_session_still_loading_moves_nobody() {
        assertNull(authRedirect(AuthState.LOADING, onSignIn = false))
        assertNull(authRedirect(AuthState.LOADING, onSignIn = true))
    }
}

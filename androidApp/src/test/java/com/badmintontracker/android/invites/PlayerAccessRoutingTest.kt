package com.badmintontracker.android.invites

import com.badmintontracker.android.nav.AuthRedirect
import com.badmintontracker.android.nav.Route
import com.badmintontracker.android.nav.authRedirect
import com.badmintontracker.android.nav.authStartDestination
import com.badmintontracker.shared.repo.AccountRole
import com.badmintontracker.shared.repo.AuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerAccessRoutingTest {

    @Test
    fun player_role_starts_on_focused_player_home() {
        assertEquals(
            Route.PlayerHome,
            authStartDestination(AuthState.AUTHENTICATED, AccountRole.PLAYER),
        )
    }

    @Test
    fun coach_role_starts_on_players() {
        assertEquals(
            Route.Players,
            authStartDestination(AuthState.AUTHENTICATED, AccountRole.COACH),
        )
    }

    @Test
    fun player_session_arriving_on_sign_in_redirects_to_player_home() {
        assertEquals(
            AuthRedirect.TO_PLAYER_HOME,
            authRedirect(AuthState.AUTHENTICATED, onSignIn = true, role = AccountRole.PLAYER),
        )
    }

    @Test
    fun unauthenticated_on_join_invite_is_not_bounced_back_to_sign_in() {
        assertNull(
            authRedirect(AuthState.UNAUTHENTICATED, onSignIn = true, role = AccountRole.COACH),
        )
    }

    @Test
    fun signed_out_player_redirects_to_sign_in() {
        assertEquals(
            AuthRedirect.TO_SIGN_IN,
            authRedirect(AuthState.UNAUTHENTICATED, onSignIn = false, role = AccountRole.PLAYER),
        )
    }
}

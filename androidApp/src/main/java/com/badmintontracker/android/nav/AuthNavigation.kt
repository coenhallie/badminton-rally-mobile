package com.badmintontracker.android.nav

import com.badmintontracker.shared.repo.AccountRole
import com.badmintontracker.shared.repo.AuthState

/**
 * Where the nav graph starts. Asked ONCE per graph: NavHost rebuilds its graph
 * when the start destination changes, and a rebuilt graph drops the back stack,
 * so a value that follows the session would throw away wherever the user was
 * every time the session status moved.
 */
fun authStartDestination(state: AuthState, role: AccountRole = AccountRole.COACH): Route =
    if (state == AuthState.AUTHENTICATED) {
        if (role == AccountRole.PLAYER) Route.PlayerHome else Route.Players
    } else {
        Route.SignIn
    }

enum class AuthRedirect { TO_SIGN_IN, TO_HOME, TO_PLAYER_HOME }

/**
 * What a change of session asks of navigation once the graph is up, or null when
 * the user stays where they are. [onSignIn] is whether an auth flow screen
 * (SignIn or JoinInvite) is on screen.
 */
fun authRedirect(
    state: AuthState,
    onSignIn: Boolean,
    role: AccountRole = AccountRole.COACH,
): AuthRedirect? = when (state) {
    AuthState.UNAUTHENTICATED -> if (onSignIn) null else AuthRedirect.TO_SIGN_IN
    AuthState.AUTHENTICATED -> if (onSignIn) {
        if (role == AccountRole.PLAYER) AuthRedirect.TO_PLAYER_HOME else AuthRedirect.TO_HOME
    } else {
        null
    }
    AuthState.LOADING -> null
}

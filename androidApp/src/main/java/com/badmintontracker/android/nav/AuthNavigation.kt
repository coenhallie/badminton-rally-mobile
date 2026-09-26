package com.badmintontracker.android.nav

import com.badmintontracker.shared.repo.AuthState

/**
 * Where the nav graph starts. Asked ONCE per graph: NavHost rebuilds its graph
 * when the start destination changes, and a rebuilt graph drops the back stack,
 * so a value that follows the session would throw away wherever the coach was
 * every time the session status moved.
 */
fun authStartDestination(state: AuthState): Route =
    if (state == AuthState.AUTHENTICATED) Route.Home else Route.SignIn

enum class AuthRedirect { TO_SIGN_IN, TO_HOME }

/**
 * What a change of session asks of navigation once the graph is up, or null when
 * the coach stays where they are. [onSignIn] is whether SignIn is on screen.
 */
fun authRedirect(state: AuthState, onSignIn: Boolean): AuthRedirect? = when (state) {
    AuthState.UNAUTHENTICATED -> if (onSignIn) null else AuthRedirect.TO_SIGN_IN
    AuthState.AUTHENTICATED -> if (onSignIn) AuthRedirect.TO_HOME else null
    AuthState.LOADING -> null
}

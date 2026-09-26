package com.badmintontracker.shared.repo

import io.github.jan.supabase.auth.status.SessionStatus

/** Platform-friendly projection of Supabase's SessionStatus (easy to consume from Swift). */
enum class AuthState { LOADING, AUTHENTICATED, UNAUTHENTICATED }

// No `else`: SessionStatus is sealed, and a status supabase-kt adds later should
// stop the build here rather than be filed under "signed out" by default, which is
// how RefreshFailure came to throw a coach out of a match.
fun SessionStatus.toAuthState(): AuthState = when (this) {
    is SessionStatus.Authenticated    -> AuthState.AUTHENTICATED
    is SessionStatus.Initializing     -> AuthState.LOADING
    // The access token expired and the refresh could not reach the server. The
    // session is still held and supabase-kt keeps retrying; one that is really
    // gone arrives as NotAuthenticated.
    is SessionStatus.RefreshFailure   -> AuthState.AUTHENTICATED
    is SessionStatus.NotAuthenticated -> AuthState.UNAUTHENTICATED
}

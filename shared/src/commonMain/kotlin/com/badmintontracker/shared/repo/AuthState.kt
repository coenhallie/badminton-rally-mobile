package com.badmintontracker.shared.repo

import io.github.jan.supabase.auth.status.SessionStatus

/** Platform-friendly projection of Supabase's SessionStatus (easy to consume from Swift). */
enum class AuthState { LOADING, AUTHENTICATED, UNAUTHENTICATED }

fun SessionStatus.toAuthState(): AuthState = when (this) {
    is SessionStatus.Authenticated -> AuthState.AUTHENTICATED
    is SessionStatus.Initializing  -> AuthState.LOADING
    else                           -> AuthState.UNAUTHENTICATED
}

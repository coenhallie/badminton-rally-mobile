package com.badmintontracker.shared.repo

import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthStateTest {
    @Test
    fun initializingMapsToLoading() {
        assertEquals(AuthState.LOADING, SessionStatus.Initializing.toAuthState())
    }

    @Test
    fun notAuthenticatedMapsToUnauthenticated() {
        assertEquals(AuthState.UNAUTHENTICATED, SessionStatus.NotAuthenticated(isSignOut = false).toAuthState())
    }

    @Test
    fun aRefreshThatFailedForWantOfSignalIsStillSignedIn() {
        // supabase-kt reports this when the access token has expired and the
        // refresh could not reach the server. The session is still held and it
        // keeps retrying; a session that is really gone arrives as
        // NotAuthenticated. A coach scoring in a hall with no signal for an hour
        // must not be thrown out to the sign-in screen mid-match.
        val status = SessionStatus.RefreshFailure(RefreshFailureCause.NetworkError(RuntimeException("offline")))
        assertEquals(AuthState.AUTHENTICATED, status.toAuthState())
    }
}

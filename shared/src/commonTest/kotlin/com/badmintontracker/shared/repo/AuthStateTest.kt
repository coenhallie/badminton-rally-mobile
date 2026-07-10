package com.badmintontracker.shared.repo

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
}

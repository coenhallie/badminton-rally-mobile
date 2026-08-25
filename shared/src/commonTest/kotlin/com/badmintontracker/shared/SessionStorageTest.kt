package com.badmintontracker.shared

import com.badmintontracker.shared.auth.SESSION_KEY
import com.badmintontracker.shared.testing.TestSupabase
import com.russhwolf.settings.MapSettings
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class SessionStorageTest {

    private val session = UserSession(
        accessToken = "test-token",
        refreshToken = "test-refresh",
        expiresIn = 3_600,
        tokenType = "Bearer",
        user = UserInfo(aud = "authenticated", id = "user-1"),
    )

    @Test
    fun the_session_is_persisted_to_the_session_store_not_the_preferences_store() = runTest {
        val prefs = MapSettings()
        val sessionStore = MapSettings()
        val client = TestSupabase.client(settings = prefs, sessionSettings = sessionStore) {
            error("no requests expected")
        }

        client.auth.importSession(session, autoRefresh = false)

        sessionStore.getStringOrNull(SESSION_KEY).shouldContain("test-refresh")
        // The whole point of S2: tokens must never land in the general-purpose store.
        prefs.getStringOrNull(SESSION_KEY).shouldBeNull()
    }
}

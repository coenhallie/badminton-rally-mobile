package com.badmintontracker.shared.auth

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** A store whose writes silently do nothing - stands in for a keychain that rejects the add. */
private fun writeFailing(delegate: Settings): Settings = object : Settings by delegate {
    override fun putString(key: String, value: String) = Unit
}

class SessionStoreMigrationTest {

    @Test
    fun moves_the_session_into_the_secure_store_and_clears_the_plaintext_copy() {
        val insecure = MapSettings(SESSION_KEY to """{"refresh_token":"abc"}""")
        val secure = MapSettings()

        migrateSession(from = insecure, to = secure)

        secure.getStringOrNull(SESSION_KEY) shouldBe """{"refresh_token":"abc"}"""
        insecure.getStringOrNull(SESSION_KEY).shouldBeNull()
    }

    @Test
    fun keeps_the_plaintext_copy_when_the_secure_write_did_not_stick() {
        // Deleting the only readable copy here would sign the user out permanently.
        val insecure = MapSettings(SESSION_KEY to """{"refresh_token":"abc"}""")
        val secure = writeFailing(MapSettings())

        migrateSession(from = insecure, to = secure)

        insecure.getStringOrNull(SESSION_KEY) shouldBe """{"refresh_token":"abc"}"""
    }

    @Test
    fun leaves_an_already_migrated_session_untouched() {
        val insecure = MapSettings()
        val secure = MapSettings(SESSION_KEY to """{"refresh_token":"live"}""")

        migrateSession(from = insecure, to = secure)

        secure.getStringOrNull(SESSION_KEY) shouldBe """{"refresh_token":"live"}"""
    }

    @Test
    fun never_overwrites_a_live_secure_session_with_a_stale_plaintext_one() {
        val insecure = MapSettings(SESSION_KEY to """{"refresh_token":"stale"}""")
        val secure = MapSettings(SESSION_KEY to """{"refresh_token":"live"}""")

        migrateSession(from = insecure, to = secure)

        secure.getStringOrNull(SESSION_KEY) shouldBe """{"refresh_token":"live"}"""
        // The stale plaintext token still has to go - leaving it defeats the point.
        insecure.getStringOrNull(SESSION_KEY).shouldBeNull()
    }

    @Test
    fun does_nothing_on_a_fresh_install() {
        val insecure = MapSettings()
        val secure = MapSettings()

        migrateSession(from = insecure, to = secure)

        secure.getStringOrNull(SESSION_KEY).shouldBeNull()
    }

    @Test
    fun is_idempotent_across_repeated_launches() {
        val insecure = MapSettings(SESSION_KEY to """{"refresh_token":"abc"}""")
        val secure = MapSettings()

        repeat(3) { migrateSession(from = insecure, to = secure) }

        secure.getStringOrNull(SESSION_KEY) shouldBe """{"refresh_token":"abc"}"""
        insecure.getStringOrNull(SESSION_KEY).shouldBeNull()
    }
}

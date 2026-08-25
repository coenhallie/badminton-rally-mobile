package com.badmintontracker.shared.auth

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

/** Stands in for a Keychain that rejects reads - the real one throws on OSStatus -34018. */
private fun readThrowing(delegate: Settings): Settings = object : Settings by delegate {
    override fun getStringOrNull(key: String): String? = error("Keychain error -34018")
}

/** Stands in for a Keychain that rejects writes. */
private fun writeThrowing(delegate: Settings): Settings = object : Settings by delegate {
    override fun putString(key: String, value: String): Unit = error("Keychain error -34018")
}

class SessionStoreResolutionTest {

    @Test
    fun uses_the_secure_store_and_migrates_into_it_when_the_keychain_works() {
        val prefs = MapSettings(SESSION_KEY to """{"refresh_token":"abc"}""")
        val keychain = MapSettings()

        val chosen = resolveSessionStore(secure = keychain, fallback = prefs)

        chosen shouldBe keychain
        keychain.getStringOrNull(SESSION_KEY).shouldContain("abc")
        prefs.getStringOrNull(SESSION_KEY) shouldBe null
    }

    @Test
    fun falls_back_instead_of_throwing_when_the_keychain_cannot_be_read() {
        // A throw here reaches createRallyApp, which is a hard crash at launch.
        val prefs = MapSettings(SESSION_KEY to """{"refresh_token":"abc"}""")
        val keychain = readThrowing(MapSettings())

        val chosen = resolveSessionStore(secure = keychain, fallback = prefs)

        chosen shouldBe prefs
    }

    @Test
    fun falls_back_instead_of_throwing_when_the_keychain_cannot_be_written() {
        val prefs = MapSettings(SESSION_KEY to """{"refresh_token":"abc"}""")
        val keychain = writeThrowing(MapSettings())

        val chosen = resolveSessionStore(secure = keychain, fallback = prefs)

        chosen shouldBe prefs
    }

    @Test
    fun keeps_the_user_signed_in_when_it_falls_back() {
        // Falling back must never cost the session - that would sign the user out
        // with no way back on an install whose account can't be re-registered.
        val prefs = MapSettings(SESSION_KEY to """{"refresh_token":"abc"}""")
        val keychain = writeThrowing(MapSettings())

        resolveSessionStore(secure = keychain, fallback = prefs)

        prefs.getStringOrNull(SESSION_KEY).shouldContain("abc")
    }

    @Test
    fun leaves_no_probe_residue_in_the_secure_store() {
        val prefs = MapSettings()
        val keychain = MapSettings()

        resolveSessionStore(secure = keychain, fallback = prefs)

        keychain.keys shouldBe emptySet()
    }

    @Test
    fun a_working_keychain_is_chosen_even_with_nothing_to_migrate() {
        val prefs = MapSettings()
        val keychain = MapSettings()

        resolveSessionStore(secure = keychain, fallback = prefs) shouldNotBe prefs
    }
}

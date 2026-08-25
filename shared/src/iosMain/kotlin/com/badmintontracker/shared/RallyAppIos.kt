package com.badmintontracker.shared

import com.badmintontracker.shared.auth.resolveSessionStore
import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.KeychainSettings
import com.russhwolf.settings.NSUserDefaultsSettings
import platform.Foundation.NSUserDefaults

/** Keychain service name for the auth session. Changing it signs every user out. */
private const val SESSION_KEYCHAIN_SERVICE = "com.badmintontracker.ios.session"

/**
 * iOS entry point. App preferences stay in NSUserDefaults; the auth session goes
 * in the Keychain - encrypted at rest, unreadable while the device is locked, and
 * out of unencrypted backups. A plaintext plist in the app container is none of those.
 */
@OptIn(ExperimentalSettingsImplementation::class)
fun createRallyApp(
    url: String,
    anonKey: String,
    /**
     * Deletes the file behind a removed local video, given its Documents-relative
     * path. Supplied by Swift because the file store lives there; passing it in
     * keeps every removal path cleaning up, not just the ones that remember to.
     */
    deleteLocalVideoFile: (String) -> Unit,
): RallyApp {
    val prefs = NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults)
    // Probes the Keychain and migrates any pre-existing NSUserDefaults session into
    // it. Returns prefs unchanged if the Keychain is unusable - a throw here would
    // abort the process before the first frame. See resolveSessionStore.
    val session = resolveSessionStore(
        secure = KeychainSettings(service = SESSION_KEYCHAIN_SERVICE),
        fallback = prefs,
        onFallback = { reason ->
            println("RallyApp: Keychain unavailable ($reason) - session stays in NSUserDefaults")
        },
    )
    return RallyApp(
        config = SupabaseConfig(url = url, anonKey = anonKey),
        settings = prefs,
        sessionSettings = session,
        onLocalVideoRemoved = { entry -> deleteLocalVideoFile(entry.uri) },
    )
}

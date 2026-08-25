package com.badmintontracker.shared.auth

import com.russhwolf.settings.Settings

/**
 * The key supabase-kt's `SettingsSessionManager` persists the session under
 * (`SettingsSessionManager.SETTINGS_KEY`). Duplicated rather than referenced
 * because that constant lives behind the auth module's internal settings source set.
 */
const val SESSION_KEY: String = "session"

/**
 * One-way move of a persisted Supabase session from an insecure store into a
 * secure one, run once at startup before the client reads its session.
 *
 * Verify-then-delete: the source copy is dropped only after the value is read
 * back intact from the destination. A keychain add can fail (for example
 * `errSecInteractionNotAllowed` before the device's first unlock), and deleting
 * the only readable copy at that point would sign the user out permanently with
 * no way back. On any failure the source is left alone and the next launch retries.
 */
fun migrateSession(from: Settings, to: Settings, key: String = SESSION_KEY) {
    // Destination already holds a session: it is the live one. Drop any leftover
    // plaintext copy - keeping it around is the very thing this migration removes.
    if (to.getStringOrNull(key) != null) {
        from.remove(key)
        return
    }
    val session = from.getStringOrNull(key) ?: return
    to.putString(key, session)
    if (to.getStringOrNull(key) == session) from.remove(key)
}

/** Written and removed by the probe in [resolveSessionStore]; never holds real data. */
private const val PROBE_KEY = "__store_probe"

/**
 * Decides where the auth session lives, once, at startup.
 *
 * Returns [secure] when a write-read-delete round trip against it actually works,
 * having first moved any existing session over. Otherwise returns [fallback] with
 * its session untouched.
 *
 * The probe exists because a `KeychainSettings` operation *throws* on an unexpected
 * OSStatus - an unsigned build raises `-34018 (missing entitlement)` on the very
 * first read. Left unhandled that propagates out of the iOS entry point and aborts
 * the process at launch, and supabase-kt touches this same store on every token
 * refresh. Deciding up front means one failure mode instead of one per call site.
 *
 * Falling back restores the previous, less private behaviour rather than crashing or
 * signing the user out; availability wins over a storage upgrade that cannot be applied.
 */
fun resolveSessionStore(
    secure: Settings,
    fallback: Settings,
    /** Called with the reason when [secure] is unusable. Diagnosing -34018 on someone
     *  else's device is impossible from a bare boolean. */
    onFallback: (String) -> Unit = {},
): Settings = runCatching {
    secure.putString(PROBE_KEY, PROBE_VALUE)
    check(secure.getStringOrNull(PROBE_KEY) == PROBE_VALUE) { "secure store did not retain a write" }
    secure.remove(PROBE_KEY)
    migrateSession(from = fallback, to = secure)
    secure
}.getOrElse { e ->
    onFallback(e.message ?: e.toString())
    fallback
}

private const val PROBE_VALUE = "1"

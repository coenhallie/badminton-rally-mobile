package com.badmintontracker.shared

import com.russhwolf.settings.Settings
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SettingsSessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.resumable.ResumableCache
import io.ktor.client.engine.HttpClientEngine

fun buildSupabaseClient(
    config: SupabaseConfig,
    settings: Settings,
    httpEngine: HttpClientEngine? = null,
    /** Override the TUS upload-url cache (tests inject an in-memory one). */
    resumableCache: ResumableCache? = null,
): SupabaseClient = createSupabaseClient(
    supabaseUrl = config.url,
    supabaseKey = config.anonKey,
) {
    httpEngine?.let { this.httpEngine = it }
    install(Auth) {
        scheme = config.deeplinkScheme
        host   = config.deeplinkHost
        sessionManager = SettingsSessionManager(settings)
    }
    install(Postgrest)
    install(Storage) {
        resumableCache?.let { resumable { cache = it } }
    }
    install(Functions)
}

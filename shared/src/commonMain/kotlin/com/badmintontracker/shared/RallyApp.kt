package com.badmintontracker.shared

import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.prefs.ThemePreferenceRepository
import com.badmintontracker.shared.repo.AnnotationsRepository
import com.badmintontracker.shared.repo.AnnotationsRepositoryImpl
import com.badmintontracker.shared.repo.AuthRepository
import com.badmintontracker.shared.repo.AuthRepositoryImpl
import com.badmintontracker.shared.repo.AuthState
import com.badmintontracker.shared.repo.ClipsRepository
import com.badmintontracker.shared.repo.ClipsRepositoryImpl
import com.badmintontracker.shared.repo.MediaRepository
import com.badmintontracker.shared.repo.MediaRepositoryImpl
import com.badmintontracker.shared.repo.SharesRepository
import com.badmintontracker.shared.repo.SharesRepositoryImpl
import com.badmintontracker.shared.repo.VideosRepository
import com.badmintontracker.shared.repo.VideosRepositoryImpl
import com.badmintontracker.shared.repo.toAuthState
import com.russhwolf.settings.Settings
import io.github.jan.supabase.SupabaseClient
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RallyApp(
    config: SupabaseConfig,
    private val settings: Settings,
    httpEngine: HttpClientEngine? = null,
) {
    val client: SupabaseClient = buildSupabaseClient(config, settings, httpEngine)
    val auth:        AuthRepository        = AuthRepositoryImpl(client)
    val clips:       ClipsRepository       = ClipsRepositoryImpl(client)
    val annotations: AnnotationsRepository = AnnotationsRepositoryImpl(client)
    val media:       MediaRepository       = MediaRepositoryImpl(client)
    val shares:      SharesRepository      = SharesRepositoryImpl(client)
    val videos:      VideosRepository      = VideosRepositoryImpl(client)

    val authState: Flow<AuthState> = auth.sessionFlow.map { it.toAuthState() }

    // On-device local video registry + annotations (shared persistence, native UI).
    val localVideos:      LocalVideoRepository       = LocalVideoRepository(settings)
    val localAnnotations: LocalAnnotationsRepository = LocalAnnotationsRepository(settings)
    val themePrefs:       ThemePreferenceRepository  = ThemePreferenceRepository(settings)
}

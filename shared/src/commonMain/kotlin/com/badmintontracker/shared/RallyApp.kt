package com.badmintontracker.shared

import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.prefs.PlaybackPreferenceRepository
import com.badmintontracker.shared.prefs.ThemePreferenceRepository
import com.badmintontracker.shared.repo.AnnotationLabelsRepository
import com.badmintontracker.shared.repo.AnnotationLabelsRepositoryImpl
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
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.russhwolf.settings.Settings
import io.github.jan.supabase.SupabaseClient
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

class RallyApp(
    config: SupabaseConfig,
    private val settings: Settings,
    /**
     * Secure store for the auth session. Defaults to [settings]; iOS passes a
     * Keychain-backed store so the refresh token never sits in a plaintext plist.
     */
    sessionSettings: Settings = settings,
    httpEngine: HttpClientEngine? = null,
    /**
     * Releases the file behind a local-video entry when that entry is removed.
     * iOS passes its file store's delete; Android's entries are content:// handles
     * into the gallery, which the app must not delete. See [LocalVideoRepository].
     */
    onLocalVideoRemoved: (LocalVideoEntry) -> Unit = {},
) {
    val client: SupabaseClient = buildSupabaseClient(config, settings, sessionSettings, httpEngine)
    val auth:        AuthRepository        = AuthRepositoryImpl(client)
    val clips:       ClipsRepository       = ClipsRepositoryImpl(client)
    val annotations: AnnotationsRepository = AnnotationsRepositoryImpl(client)
    val labels:      AnnotationLabelsRepository = AnnotationLabelsRepositoryImpl(client, settings)
    val media:       MediaRepository       = MediaRepositoryImpl(client)
    val shares:      SharesRepository      = SharesRepositoryImpl(client)
    val videos:      VideosRepository      = VideosRepositoryImpl(client)

    val authState: Flow<AuthState> = auth.sessionFlow.map { it.toAuthState() }

    // On-device local video registry + annotations (shared persistence, native UI).
    val localVideos:      LocalVideoRepository         = LocalVideoRepository(settings, onLocalVideoRemoved)
    val localAnnotations: LocalAnnotationsRepository   = LocalAnnotationsRepository(settings)
    val themePrefs:       ThemePreferenceRepository    = ThemePreferenceRepository(settings)
    val playbackPrefs:    PlaybackPreferenceRepository = PlaybackPreferenceRepository(settings)

    // Matches scored on this phone. Local first, like the video registry above it:
    // a match is created and scored courtside, where there is usually no signal.
    val scoreLogs: ScoreLogsRepository = ScoreLogsRepository(client, settings, Clock.System::now)
}

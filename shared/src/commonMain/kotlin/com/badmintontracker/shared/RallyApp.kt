package com.badmintontracker.shared

import com.badmintontracker.shared.local.LocalAnalysisCoordinator
import com.badmintontracker.shared.local.LocalInferenceEngine
import com.badmintontracker.shared.localvideo.AnalyzeCoordinator
import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.localvideo.orphanedLocalVideoIds
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
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
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
    val scoreLogs: ScoreLogsRepository = ScoreLogsRepository(
        client, settings, Clock.System::now,
        onSynced = { knownScoreLogIds ->
            // A video whose match is gone is invisible: every "on this phone" list
            // filters to entries with no scoreLogId, and there is no match row left
            // to show it under. Detaching gives it back to the local video list.
            //
            // Wired here for the same reason onVideoRowReady below is: these are two
            // repositories that must not know about each other, and this is where
            // the app graph already joins them.
            orphanedLocalVideoIds(localVideos.entries.value, knownScoreLogIds)
                .forEach { id ->
                    // resultSeen too, and this is not tidiness. Both platforms
                    // raise the "no rallies found" dialog for the first standalone
                    // entry that is FAILED and unseen, so detaching an old orphan
                    // makes it eligible and it interrupts the next launch with the
                    // outcome of a run inside a match that no longer exists. Seen
                    // on a real device the first time this shipped. The failure is
                    // not hidden: the row itself still shows it, with Retry.
                    localVideos.update(id) { it.copy(scoreLogId = null, resultSeen = true) }
                }
        },
    )

    /**
     * Builds the on-device analyze pipeline. Both platforms call this rather
     * than constructing a coordinator themselves, for the same reason as
     * [analyzeCoordinator] below: the sequencing must not be able to differ
     * per platform.
     *
     * The platform supplies only [engine] - decode and inference. Everything
     * downstream of raw model output is shared code, which is what the
     * section 5.1 boundary is for.
     */
    fun localAnalysisCoordinator(
        engine: LocalInferenceEngine,
        log: (String) -> Unit = {},
    ): LocalAnalysisCoordinator = LocalAnalysisCoordinator(engine = engine, log = log)

    /**
     * Builds the analyze pipeline with the scoring link already wired in. Both
     * platforms call this rather than constructing a coordinator themselves, so
     * "a finished upload binds its match" cannot be true on one phone and not the
     * other.
     */
    fun analyzeCoordinator(
        scope: CoroutineScope,
        openChannel: suspend (uri: String, offset: Long) -> ByteReadChannel,
        log: (String) -> Unit = {},
    ): AnalyzeCoordinator = AnalyzeCoordinator(
        localVideos = localVideos,
        videos = videos,
        clips = clips,
        scope = scope,
        openChannel = openChannel,
        log = log,
        localAnnotations = localAnnotations,
        onVideoRowReady = { entryId ->
            // The videos row now exists, so the foreign key can be satisfied. A
            // video-first import has no match and this is a no-op.
            localVideos.get(entryId)?.scoreLogId?.let { scoreLogs.attachVideo(it, entryId) }
        },
    )
}

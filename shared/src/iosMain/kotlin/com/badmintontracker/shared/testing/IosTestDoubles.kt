package com.badmintontracker.shared.testing

import com.badmintontracker.shared.localvideo.AnalyzeCoordinator
import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.model.CourtKeypoints
import com.badmintontracker.shared.model.MatchMetadata
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.repo.ClipsRepository
import com.badmintontracker.shared.repo.ProcessingUpdate
import com.badmintontracker.shared.repo.UploadState
import com.badmintontracker.shared.repo.VideosRepository
import com.russhwolf.settings.NSUserDefaultsSettings
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import platform.Foundation.NSUserDefaults

/**
 * Test doubles the iOS test bundle can build, mirroring [testScoreLogsRepository]
 * above: Swift cannot implement a Kotlin interface that has suspend members, and
 * these types have several, so a Swift-side fake is not an option the way it is
 * for a plain protocol. Nothing in the app calls anything on this page - `RallyApp`
 * wires the real repositories.
 *
 * Trivial stubs throughout: `MatchModelTests` (the only consumer) never triggers
 * the analyze pipeline, so nothing here needs to do more than satisfy the
 * constructors it depends on.
 */
private class NoopVideosRepository : VideosRepository {
    override suspend fun createVideo(
        videoId: String, filename: String, sizeBytes: Long, title: String?, description: String?,
    ): Result<Unit> = Result.success(Unit)
    override suspend fun setCourtKeypoints(videoId: String, keypoints: CourtKeypoints): Result<Unit> =
        Result.success(Unit)
    override suspend fun startProcessing(videoId: String): Result<Unit> = Result.success(Unit)
    override fun observeProcessing(videoId: String, pollIntervalMs: Long): Flow<ProcessingUpdate> = emptyFlow()
    override fun uploadVideo(
        videoId: String, sizeBytes: Long, channelProvider: suspend (offset: Long) -> ByteReadChannel,
    ): Flow<UploadState> = emptyFlow()
    override suspend fun listMatchMetadata(): Result<List<MatchMetadata>> = Result.success(emptyList())
    override suspend fun deleteMatch(videoId: String): Result<Unit> = Result.success(Unit)
}

private class InMemoryClipsRepository : ClipsRepository {
    val clips = MutableStateFlow<List<RallyClip>>(emptyList())
    override suspend fun listClips(): List<RallyClip> = clips.value
    override fun observeClips(): Flow<List<RallyClip>> = clips
    override suspend fun refresh() = Unit
    override suspend fun updateTitle(clipId: String, title: String?): Result<Unit> = Result.success(Unit)
    override suspend fun countClipsForVideo(videoId: String): Result<Int> =
        Result.success(clips.value.count { it.videoId == videoId })
    override fun pruneVideo(videoId: String) {
        clips.value = clips.value.filterNot { it.videoId == videoId }
    }
}

/** A [LocalVideoRepository] the iOS test bundle can build. See this file's doc comment. */
fun testLocalVideoRepository(): LocalVideoRepository {
    val settings = NSUserDefaultsSettings(NSUserDefaults(suiteName = TEST_LOCAL_VIDEOS_SUITE))
    settings.clear()
    return LocalVideoRepository(settings)
}

/**
 * A [ClipsRepository] the iOS test bundle can build, for wherever a test needs
 * clips on screen without a coordinator alongside it (see this file's doc
 * comment). Mutate `clips.value` directly through the same [InMemoryClipsRepository]
 * instance to seed clips for a test.
 */
fun testClipsRepository(): ClipsRepository = InMemoryClipsRepository()

/**
 * An [AnalyzeCoordinator] the iOS test bundle can build, wired to the fixed
 * [localVideos] and [clips] under test and to a no-op [VideosRepository] - see
 * this file's doc comment.
 */
fun testAnalyzeCoordinator(localVideos: LocalVideoRepository, clips: ClipsRepository): AnalyzeCoordinator =
    AnalyzeCoordinator(
        localVideos = localVideos,
        videos = NoopVideosRepository(),
        clips = clips,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        openChannel = { _, _ -> ByteReadChannel(ByteArray(0)) },
        localAnnotations = LocalAnnotationsRepository(NSUserDefaultsSettings(NSUserDefaults(suiteName = TEST_LOCAL_ANNOTATIONS_SUITE)).also { it.clear() }),
    )

private const val TEST_LOCAL_VIDEOS_SUITE = "com.badmintontracker.ios.tests.localvideos"
private const val TEST_LOCAL_ANNOTATIONS_SUITE = "com.badmintontracker.ios.tests.localannotations"

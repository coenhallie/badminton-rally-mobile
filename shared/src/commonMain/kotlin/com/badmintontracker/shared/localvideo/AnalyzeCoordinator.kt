package com.badmintontracker.shared.localvideo

import com.badmintontracker.shared.model.CourtKeypoints
import com.badmintontracker.shared.repo.ClipsRepository
import com.badmintontracker.shared.repo.UploadState
import com.badmintontracker.shared.repo.VideoStatus
import com.badmintontracker.shared.repo.VideosRepository
import com.badmintontracker.shared.util.SyncLock
import com.badmintontracker.shared.util.withLock
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Transient per-entry progress; durable state lives in [LocalVideoRepository]. */
data class AnalyzeProgress(
    val entryId: String,
    val uploadProgress: Float? = null,
    val pipelineProgress: Float? = null,
)

/**
 * Application-scoped orchestrator for the local-video analyze pipeline:
 * UPLOAD -> CREATE_ROW -> KEYPOINTS -> TRIGGER -> PROCESSING.
 * Runs in an app-scoped coroutine so it survives navigation (not process death;
 * persisted stages let a relaunch resume via [retry]/[reattachToProcessing]).
 */
class AnalyzeCoordinator(
    private val localVideos: LocalVideoRepository,
    private val videos: VideosRepository,
    private val clips: ClipsRepository,
    private val scope: CoroutineScope,
    private val openChannel: suspend (uri: String, offset: Long) -> ByteReadChannel,
    private val log: (String) -> Unit = {},
    private val localAnnotations: LocalAnnotationsRepository,
    /**
     * Called once the videos row for this entry is known to exist, and before the
     * entry is removed. Deliberately an entry id and a moment rather than anything
     * about matches: this class is a pipeline over one video and must not grow a
     * dependency on scoring.
     */
    private val onVideoRowReady: (entryId: String) -> Unit = {},
    /**
     * Fetch this video's cloud pose artifact and store it, if there is one.
     *
     * Injected the way [openChannel] is: the stores are platform types, so the
     * platform supplies the capability and this owns the sequencing. The
     * default is a no-op, which is what every test that does not care about
     * Phase 2 gets.
     *
     * Must not throw. A Phase 2 that produced nothing is not a Phase 1
     * failure, and the caller below deliberately does not treat it as one.
     */
    private val installCloudPoses: suspend (videoId: String, onProgress: (Float) -> Unit) -> Unit =
        { _, _ -> },
    /**
     * Returns once the stored session has been restored, whether or not it turned
     * out to hold anyone.
     *
     * [reattachToProcessing] runs at launch and supabase-kt restores the session
     * asynchronously, so without this the first run of a cold start talks to the
     * backend as nobody: an interrupted upload fails with "Not signed in", and
     * [onVideoRowReady] reaches a scoring store that has not read its cache yet.
     */
    private val awaitSession: suspend () -> Unit = {},
) {
    private val _progress = MutableStateFlow<Map<String, AnalyzeProgress>>(emptyMap())
    val progress: StateFlow<Map<String, AnalyzeProgress>> = _progress.asStateFlow()

    private val _hasActiveUpload = MutableStateFlow(false)
    val hasActiveUpload: StateFlow<Boolean> = _hasActiveUpload.asStateFlow()

    private val _finishedRuns = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Cloud runs that have finished successfully, for the announcement rule a
     * device run already goes through.
     *
     * The device runner is asked the same question through its own states, and
     * `announceFinishedRuns` decides whether finishing is announced on the match
     * list. A cloud run had no way to answer it at all: [finish] keeps its
     * entry, so the only thing the list could observe was the row's status
     * line changing. The coach was left on the list with nothing saying the
     * result was ready or where to find it.
     *
     * Accumulates rather than being assigned, because a finished run has no
     * later state to clear it from. That is the shape `announceFinishedRuns`
     * already expects: it keeps its own seen set and announces a run once.
     */
    val finishedRuns: StateFlow<Set<String>> = _finishedRuns.asStateFlow()

    // Guarded by itself: mutated from concurrent pipeline coroutines on scope.
    private val activeLock = SyncLock()
    private val active = mutableSetOf<String>()

    // Uploads can overlap across entries; the keep-awake flag must only drop
    // when the LAST one finishes, so track the set and derive the boolean.
    private val uploadingLock = SyncLock()
    private val uploading = mutableSetOf<String>()

    private fun setUploading(entryId: String, isUploading: Boolean) {
        uploadingLock.withLock {
            if (isUploading) uploading.add(entryId) else uploading.remove(entryId)
            _hasActiveUpload.value = uploading.isNotEmpty()
        }
    }

    fun startAnalysis(entryId: String, keypoints: CourtKeypoints) {
        localVideos.setKeypoints(entryId, keypoints)
        launchPipeline(entryId, AnalyzeStep.UPLOAD)
    }

    fun retry(entryId: String) {
        when (val failed = localVideos.get(entryId)?.failedStep) {
            AnalyzeStep.PROCESSING -> launch(entryId) { retryProcessing(entryId) }
            null -> launchPipeline(entryId, AnalyzeStep.UPLOAD)
            else -> launchPipeline(entryId, failed)
        }
    }

    /**
     * A PROCESSING failure is one of two very different things, and only the
     * videos row can say which.
     *
     * The backend's job failed: the row sits in a terminal failed_* status, and
     * resuming at PROCESSING would re-read it and fail again at once, so the edge
     * function has to be re-triggered.
     *
     * Or the PHONE lost the server for three minutes while the job carried on.
     * Re-triggering that resets the row to "uploaded" and starts Phase 1 again
     * from nothing, possibly beside the worker still finishing the first run.
     * That one only needs its poll resumed.
     */
    private suspend fun retryProcessing(entryId: String) {
        awaitSession()
        // Spinning before the read rather than after: with no signal the read
        // takes as long to give up as the poll did, and a Retry that leaves the
        // row looking untouched for three minutes reads as a dead button.
        localVideos.update(entryId) {
            it.copy(stage = AnalyzeStage.PROCESSING, failedStep = null, failureMessage = null)
        }
        val row = videos.observeProcessing(entryId).first()
        val backendFailed = row.isFailure && row.status != VideoStatus.FAILED_CONNECTION
        runPipeline(entryId, if (backendFailed) AnalyzeStep.TRIGGER else AnalyzeStep.PROCESSING)
    }

    /** Re-attach to backend jobs that kept running while the app was dead. */
    fun reattachToProcessing() {
        localVideos.entries.value.forEach { entry ->
            when (entry.stage) {
                AnalyzeStage.PROCESSING -> launchPipeline(entry.id, AnalyzeStep.PROCESSING)
                // Process died before the trigger succeeded; the upload itself is
                // resumable and the later steps tolerate re-runs, so restart the
                // pipeline instead of leaving the entry stuck on a spinner.
                AnalyzeStage.UPLOADING -> launchPipeline(entry.id, AnalyzeStep.UPLOAD)
                // Phase 1 is done and its clips exist; only the movement pass was
                // cut short. MEASURING counts as running, so an entry left here can
                // be neither removed nor retried, and nothing else would ever move
                // it on.
                AnalyzeStage.MEASURING -> launch(entry.id) { resumeMeasuring(entry.id) }
                else -> Unit
            }
        }
    }

    private fun launchPipeline(entryId: String, startFrom: AnalyzeStep) =
        launch(entryId) { runPipeline(entryId, startFrom) }

    /** One run per entry at a time, whichever kind of run it is. */
    private fun launch(entryId: String, run: suspend () -> Unit) {
        if (!activeLock.withLock { active.add(entryId) }) return
        scope.launch {
            try {
                run()
            } finally {
                activeLock.withLock { active.remove(entryId) }
                _progress.update { it - entryId }
            }
        }
    }

    private suspend fun runPipeline(entryId: String, startFrom: AnalyzeStep) {
        awaitSession()
        val entry = localVideos.get(entryId) ?: return
        val keypoints = entry.keypoints
            ?: return fail(entryId, AnalyzeStep.KEYPOINTS, "No court points saved")

        if (startFrom <= AnalyzeStep.UPLOAD) {
            localVideos.update(entryId) {
                it.copy(stage = AnalyzeStage.UPLOADING, failedStep = null, failureMessage = null)
            }
            setUploading(entryId, true)
            var failure: String? = null
            try {
                videos.uploadVideo(entry.id, entry.sizeBytes) { offset -> openChannel(entry.uri, offset) }
                    .collect { state ->
                        when (state) {
                            is UploadState.InProgress ->
                                setProgress(entryId) { it.copy(uploadProgress = state.progress) }
                            is UploadState.Failed -> failure = state.message
                            UploadState.Done -> Unit
                        }
                    }
            } finally {
                setUploading(entryId, false)
            }
            failure?.let { return fail(entryId, AnalyzeStep.UPLOAD, it) }
        }

        if (startFrom <= AnalyzeStep.CREATE_ROW) {
            val error = videos.createVideo(
                entry.id, entry.displayName, entry.sizeBytes, entry.title, entry.description,
            ).exceptionOrNull()
            if (error != null && !error.isDuplicateKey()) {
                return fail(entryId, AnalyzeStep.CREATE_ROW, error.message ?: "Couldn't register video")
            }
        }

        // Unconditional, not inside the branch above: retry() resumes from the
        // failed step, so a run that failed at TRIGGER never re-enters CREATE_ROW,
        // and the row exists on every path that reaches here.
        onVideoRowReady(entry.id)

        if (startFrom <= AnalyzeStep.KEYPOINTS) {
            videos.setCourtKeypoints(entry.id, keypoints).onFailure {
                return fail(entryId, AnalyzeStep.KEYPOINTS, it.message ?: "Couldn't save court points")
            }
        }

        if (startFrom <= AnalyzeStep.TRIGGER) {
            videos.startProcessing(entry.id).onFailure {
                return fail(entryId, AnalyzeStep.TRIGGER, it.message ?: "Couldn't start analysis")
            }
        }

        localVideos.update(entryId) {
            it.copy(stage = AnalyzeStage.PROCESSING, failedStep = null, failureMessage = null)
        }
        var pipelineError: String? = null
        var terminalStatus = ""
        videos.observeProcessing(entry.id).collect { update ->
            terminalStatus = update.status
            setProgress(entryId) { it.copy(pipelineProgress = update.progress) }
            if (update.isFailure) pipelineError = update.error ?: "Analysis failed"
        }
        pipelineError?.let {
            log("video ${entry.id} pipeline failed ($terminalStatus): $it")
            return fail(entryId, AnalyzeStep.PROCESSING, it)
        }

        // Count the clips directly on the server: the local cache can miss them when
        // its refresh fails, and the clip rows can lag the terminal status flip. A
        // zero is only trusted after retries, and a count that never succeeded proves
        // nothing — then the pipeline's own success verdict wins over a false failure.
        var clipCount: Int? = null
        for (attempt in 1..CLIP_COUNT_ATTEMPTS) {
            clipCount = clips.countClipsForVideo(entry.id).getOrNull() ?: clipCount
            if ((clipCount ?: 0) > 0) break
            if (attempt < CLIP_COUNT_ATTEMPTS) delay(CLIP_COUNT_RETRY_MS)
        }
        runCatching { clips.refresh() }
        log("video ${entry.id} terminal=$terminalStatus clips=${clipCount ?: "unknown"}")
        if (clipCount == 0) {
            // Pipeline succeeded but detected no rallies — don't vanish silently.
            return fail(
                entryId,
                AnalyzeStep.PROCESSING,
                "Analysis finished but found no rallies in this video.",
            )
        }
        // Phase 2, and everything it needs, BEFORE the branch below: that
        // branch removes the entry for a video with no annotations, and both
        // stores are keyed by entry id. Installing afterwards would write a
        // track and a skeleton against an id the registry no longer lists.
        //
        // MEASURING is set before the wait rather than after, because it is
        // what makes canRemoveLocalVideo false: a coach who swiped the row
        // away mid-run would otherwise leave the finished artifact with no
        // entry to land against.
        measureMovement(entryId, terminalStatus)
        finish(entryId)
    }

    /**
     * Picks a run up from MEASURING after the process died in it.
     *
     * The row is read first because what is left to do depends on how far the
     * backend got on its own, and [measureMovement] cannot find that out by
     * trying: start-analytics answers 409 to a row that is already running or
     * done, and a refused start reads as "no heatmap this time".
     */
    private suspend fun resumeMeasuring(entryId: String) {
        awaitSession()
        if (localVideos.get(entryId) == null) return
        val status = videos.observeProcessing(entryId).first().status
        measureMovement(entryId, status)
        finish(entryId)
    }

    private fun finish(entryId: String) {
        // Before the update below, and about the RUN, not about whether its
        // video happened to survive.
        _finishedRuns.update { it + entryId }

        // The entry is KEPT, always, and this must not become conditional again.
        //
        // It used to be removed for a video with no annotations, on the reasoning
        // that the clips live in the cloud and the on-device copy is dead weight.
        // Two things make that wrong. The removal hook deletes what an analysis
        // produced, so it threw away the cloud track and skeleton written seconds
        // earlier by measureMovement. And even with the artifacts spared, the
        // skeleton is drawn OVER playback: releasing the footage left an 11MB
        // artifact the app could never display, after the coach had waited on a
        // GPU for it.
        //
        // Freeing the storage is the coach's call, through the same remove the
        // match list already offers, which discards the analysis with it.
        localVideos.update(entryId) {
            it.copy(stage = AnalyzeStage.ANALYZED, failedStep = null, failureMessage = null)
        }
    }

    /**
     * Runs Phase 2 and installs what it produced.
     *
     * Deliberately swallows every failure. Phase 1 has already succeeded by
     * the time this runs - the clips exist and are watchable - and a heatmap
     * that could not be fetched is not a reason to paint the row red and ask
     * the coach to analyse the whole video again. The failures are logged and
     * the entry is left exactly where Phase 1 left it.
     *
     * The three ways this ends with no heatmap, none of them a failure of
     * this video: start-analytics refuses (the row was not in a state it
     * accepts), Phase 2 runs and cannot upload its artifact (non-fatal on the
     * worker, so results_meta carries a null path), or the download breaks.
     */
    private suspend fun measureMovement(entryId: String, rowStatus: String) {
        localVideos.update(entryId) { it.copy(stage = AnalyzeStage.MEASURING) }
        setProgress(entryId) { it.copy(pipelineProgress = null) }

        // [rowStatus] is where the videos row stands now. Straight after Phase 1
        // that is phase1_complete; a run picked up after the process died can find
        // Phase 2 already going, or already done.
        when (rowStatus) {
            VideoStatus.COMPLETED -> Unit
            VideoStatus.PROCESSING_PHASE2 -> if (!awaitPhaseTwo(entryId)) return
            VideoStatus.PHASE1_COMPLETE, FAILED_PHASE2 -> {
                videos.startAnalytics(entryId).onFailure {
                    log("video $entryId phase 2 not started: ${it.message}")
                    return
                }
                if (!awaitPhaseTwo(entryId)) return
            }
            else -> {
                log("video $entryId phase 2 skipped: row is $rowStatus")
                return
            }
        }

        runCatching { installCloudPoses(entryId) { p -> setProgress(entryId) { s -> s.copy(pipelineProgress = p) } } }
            .onFailure { log("video $entryId pose artifact not installed: ${it.message}") }
    }

    /** False when Phase 2 ended in a failure, which is logged and goes no further. */
    private suspend fun awaitPhaseTwo(entryId: String): Boolean {
        var failed: String? = null
        videos.observeProcessing(entryId, awaitAnalytics = true).collect { update ->
            setProgress(entryId) { it.copy(pipelineProgress = update.progress) }
            if (update.isFailure) failed = update.error ?: "Phase 2 failed"
        }
        failed?.let { log("video $entryId phase 2 failed: $it") }
        return failed == null
    }

    private fun fail(entryId: String, step: AnalyzeStep, message: String) {
        localVideos.update(entryId) {
            // resultSeen = false so the UI shows the result dialog for this new failure.
            it.copy(stage = AnalyzeStage.FAILED, failedStep = step, failureMessage = message, resultSeen = false)
        }
    }

    private fun setProgress(entryId: String, transform: (AnalyzeProgress) -> AnalyzeProgress) {
        _progress.update { it + (entryId to transform(it[entryId] ?: AnalyzeProgress(entryId))) }
    }
}

private fun Throwable.isDuplicateKey(): Boolean =
    message?.contains("duplicate key") == true || message?.contains("23505") == true

// The status start-analytics leaves behind when Phase 2 fails, and one of the two
// it accepts a (re)start from.
private const val FAILED_PHASE2 = "failed_phase2"
private const val CLIP_COUNT_ATTEMPTS = 3
private const val CLIP_COUNT_RETRY_MS = 2_000L

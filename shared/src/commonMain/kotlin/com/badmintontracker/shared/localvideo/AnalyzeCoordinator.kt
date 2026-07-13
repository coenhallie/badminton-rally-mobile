package com.badmintontracker.shared.localvideo

import com.badmintontracker.shared.model.CourtKeypoints
import com.badmintontracker.shared.repo.ClipsRepository
import com.badmintontracker.shared.repo.UploadState
import com.badmintontracker.shared.repo.VideosRepository
import com.badmintontracker.shared.util.SyncLock
import com.badmintontracker.shared.util.withLock
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
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
) {
    private val _progress = MutableStateFlow<Map<String, AnalyzeProgress>>(emptyMap())
    val progress: StateFlow<Map<String, AnalyzeProgress>> = _progress.asStateFlow()

    private val _hasActiveUpload = MutableStateFlow(false)
    val hasActiveUpload: StateFlow<Boolean> = _hasActiveUpload.asStateFlow()

    // Guarded by itself: mutated from concurrent pipeline coroutines on scope.
    private val activeLock = SyncLock()
    private val active = mutableSetOf<String>()

    fun startAnalysis(entryId: String, keypoints: CourtKeypoints) {
        localVideos.update(entryId) { it.copy(keypoints = keypoints) }
        launchPipeline(entryId, AnalyzeStep.UPLOAD)
    }

    fun retry(entryId: String) {
        val step = localVideos.get(entryId)?.failedStep ?: AnalyzeStep.UPLOAD
        launchPipeline(entryId, step)
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
                else -> Unit
            }
        }
    }

    private fun launchPipeline(entryId: String, startFrom: AnalyzeStep) {
        if (!activeLock.withLock { active.add(entryId) }) return
        scope.launch {
            try {
                runPipeline(entryId, startFrom)
            } finally {
                activeLock.withLock { active.remove(entryId) }
                _progress.update { it - entryId }
            }
        }
    }

    private suspend fun runPipeline(entryId: String, startFrom: AnalyzeStep) {
        val entry = localVideos.get(entryId) ?: return
        val keypoints = entry.keypoints
            ?: return fail(entryId, AnalyzeStep.KEYPOINTS, "No court points saved")

        if (startFrom <= AnalyzeStep.UPLOAD) {
            localVideos.update(entryId) {
                it.copy(stage = AnalyzeStage.UPLOADING, failedStep = null, failureMessage = null)
            }
            _hasActiveUpload.value = true
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
                _hasActiveUpload.value = false
            }
            failure?.let { return fail(entryId, AnalyzeStep.UPLOAD, it) }
        }

        if (startFrom <= AnalyzeStep.CREATE_ROW) {
            val error = videos.createVideo(entry.id, entry.displayName, entry.sizeBytes).exceptionOrNull()
            if (error != null && !error.isDuplicateKey()) {
                return fail(entryId, AnalyzeStep.CREATE_ROW, error.message ?: "Couldn't register video")
            }
        }

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

        runCatching { clips.refresh() }
        val clipCount = runCatching { clips.observeClips().first().count { it.videoId == entry.id } }
            .getOrDefault(0)
        log("video ${entry.id} terminal=$terminalStatus clips=$clipCount")
        if (clipCount == 0) {
            // Pipeline succeeded but detected no rallies — don't vanish silently.
            return fail(
                entryId,
                AnalyzeStep.PROCESSING,
                "Analysis finished but found no rallies in this video.",
            )
        }
        if (localAnnotations.hasAnnotations(entryId)) {
            // Keep annotated videos so the notes survive; mark them Analyzed.
            localVideos.update(entryId) {
                it.copy(stage = AnalyzeStage.ANALYZED, failedStep = null, failureMessage = null)
            }
        } else {
            localVideos.remove(entryId)
        }
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

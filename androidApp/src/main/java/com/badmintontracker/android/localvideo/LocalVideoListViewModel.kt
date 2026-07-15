package com.badmintontracker.android.localvideo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.shared.localvideo.AnalyzeCoordinator
import com.badmintontracker.shared.localvideo.AnalyzeProgress
import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Render model: durable entry merged with the coordinator's transient progress. */
data class LocalVideoRow(
    val entry: LocalVideoEntry,
    val statusText: String?,     // null when plain LOCAL
    val durationText: String,    // m:ss
    val canAnalyze: Boolean,     // LOCAL or FAILED
    val analyzeLabel: String,    // "Analyze", or "Re-analyze" after a failed attempt
)

class LocalVideoListViewModel(
    private val localVideos: LocalVideoRepository,
    private val coordinator: AnalyzeCoordinator,
    private val localAnnotations: LocalAnnotationsRepository,
) : ViewModel() {

    val rows = combine(localVideos.entries, coordinator.progress) { entries, progress ->
        entries.map { e -> e.toRow(progress[e.id]) }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        localVideos.entries.value.map { it.toRow(null) },
    )

    fun remove(id: String) {
        localVideos.remove(id)
        localAnnotations.removeAllFor(id)
    }

    fun retry(id: String) = coordinator.retry(id)

    /** Marks a failure's result dialog as shown so it isn't re-displayed. */
    fun acknowledgeResult(id: String) = localVideos.update(id) { it.copy(resultSeen = true) }
}

internal fun LocalVideoEntry.toRow(progress: AnalyzeProgress?): LocalVideoRow {
    val statusText = when (stage) {
        AnalyzeStage.LOCAL -> null
        AnalyzeStage.UPLOADING -> progress?.uploadProgress
            ?.let { "Uploading ${(it * 100).toInt()}%…" } ?: "Uploading…"
        AnalyzeStage.PROCESSING -> progress?.pipelineProgress
            ?.let { "Analyzing ${(it * 100).toInt()}%…" } ?: "Analyzing…"
        // Failures are surfaced via a result dialog, not inline card text.
        AnalyzeStage.FAILED -> null
        AnalyzeStage.ANALYZED -> "Analyzed"
    }
    return LocalVideoRow(
        entry = this,
        statusText = statusText,
        durationText = formatDuration(durationMs),
        canAnalyze = stage == AnalyzeStage.LOCAL || stage == AnalyzeStage.FAILED,
        analyzeLabel = analyzeButtonLabel(stage),
    )
}

/**
 * "Re-analyze" once an attempt has failed (the video keeps its saved court points
 * and resumes from the failed step); "Analyze" for a fresh video.
 */
internal fun analyzeButtonLabel(stage: AnalyzeStage): String =
    if (stage == AnalyzeStage.FAILED) "Re-analyze" else "Analyze"

internal fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    return "${totalSec / 60}:${(totalSec % 60).toString().padStart(2, '0')}"
}

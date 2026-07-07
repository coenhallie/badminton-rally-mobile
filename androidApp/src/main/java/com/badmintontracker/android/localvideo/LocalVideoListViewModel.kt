package com.badmintontracker.android.localvideo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Render model: durable entry merged with the coordinator's transient progress. */
data class LocalVideoRow(
    val entry: LocalVideoEntry,
    val statusText: String?,     // null when plain LOCAL
    val durationText: String,    // m:ss
    val canAnalyze: Boolean,     // LOCAL or FAILED
)

class LocalVideoListViewModel(
    private val localVideos: LocalVideoRepository,
    private val coordinator: AnalyzeCoordinator,
) : ViewModel() {

    val rows = combine(localVideos.entries, coordinator.progress) { entries, progress ->
        entries.map { e -> e.toRow(progress[e.id]) }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        localVideos.entries.value.map { it.toRow(null) },
    )

    fun remove(id: String) = localVideos.remove(id)
    fun retry(id: String) = coordinator.retry(id)
}

internal fun LocalVideoEntry.toRow(progress: AnalyzeProgress?): LocalVideoRow {
    val statusText = when (stage) {
        AnalyzeStage.LOCAL -> null
        AnalyzeStage.UPLOADING -> progress?.uploadProgress
            ?.let { "Uploading ${(it * 100).toInt()}%…" } ?: "Uploading…"
        AnalyzeStage.PROCESSING -> progress?.pipelineProgress
            ?.let { "Analyzing ${(it * 100).toInt()}%…" } ?: "Analyzing…"
        AnalyzeStage.FAILED -> "Failed: ${failureMessage ?: "unknown error"} — tap Analyze to retry"
    }
    return LocalVideoRow(
        entry = this,
        statusText = statusText,
        durationText = formatDuration(durationMs),
        canAnalyze = stage == AnalyzeStage.LOCAL || stage == AnalyzeStage.FAILED,
    )
}

internal fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    return "${totalSec / 60}:${(totalSec % 60).toString().padStart(2, '0')}"
}

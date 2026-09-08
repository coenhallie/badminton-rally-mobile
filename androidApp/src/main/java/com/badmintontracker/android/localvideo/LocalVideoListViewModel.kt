package com.badmintontracker.android.localvideo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badmintontracker.android.localanalysis.LocalAnalysisState
import com.badmintontracker.android.localanalysis.isDeviceRunInFlight
import com.badmintontracker.android.localanalysis.toDeviceWork
import com.badmintontracker.shared.localvideo.AnalyzeCoordinator
import com.badmintontracker.shared.localvideo.AnalyzeProgress
import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.localvideo.canEditLocalVideoDetails
import com.badmintontracker.shared.localvideo.canRemoveLocalVideo
import com.badmintontracker.shared.localvideo.cloudAnalysisStatus
import com.badmintontracker.shared.localvideo.deviceWorkLabel
import com.badmintontracker.shared.localvideo.isAnalysisRunning
import com.badmintontracker.shared.localvideo.normalizeDescription
import com.badmintontracker.shared.localvideo.normalizeTitle
import com.badmintontracker.shared.localvideo.setDetails
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Render model: the durable entry merged with BOTH pipelines' transient
 * progress - the cloud coordinator's, and the on-device runner's.
 *
 * The device run has to be merged in here rather than read by the row: it never
 * moves `entry.stage`, so every rule phrased on the stage alone (canAnalyze,
 * canRemove, "is anything happening") is blind to it, and a row built from the
 * stage sits there offering "Analyze" over the run it just started.
 */
data class LocalVideoRow(
    val entry: LocalVideoEntry,
    val primaryText: String,     // the user's match name, else the file name
    val statusText: String?,     // null when plain LOCAL and no device run
    val durationText: String,    // m:ss
    val canAnalyze: Boolean,     // LOCAL or FAILED, and neither pipeline in flight
    val analyzeLabel: String,    // "Analyze", or "Re-analyze" after a failed attempt
    val canRemove: Boolean,      // hidden while either pipeline is working on the file
    val canEditDetails: Boolean, // LOCAL only: metadata rides on the videos INSERT (shared rule)
    /** Whether to spin: either pipeline is working on this video right now. */
    val isBusy: Boolean,
)

class LocalVideoListViewModel(
    private val localVideos: LocalVideoRepository,
    private val coordinator: AnalyzeCoordinator,
    private val localAnnotations: LocalAnnotationsRepository,
    /**
     * The device runner's state, as a flow rather than the runner itself: this
     * class needs nothing else from it, and depending on the object would drag
     * a Context into a fold over three streams. The same call
     * BackgroundWorkMonitor makes, for the same reason.
     */
    deviceStates: StateFlow<Map<String, LocalAnalysisState>>,
) : ViewModel() {

    val rows = combine(
        localVideos.entries,
        coordinator.progress,
        deviceStates,
    ) { entries, progress, device ->
        entries.map { e -> e.toRow(progress[e.id], device[e.id] ?: LocalAnalysisState.Idle) }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        localVideos.entries.value.map { it.toRow(null, deviceStates.value[it.id] ?: LocalAnalysisState.Idle) },
    )

    fun remove(id: String) {
        localVideos.remove(id)
        localAnnotations.removeAllFor(id)
    }

    fun retry(id: String) = coordinator.retry(id)

    /** Marks a failure's result dialog as shown so it isn't re-displayed. */
    fun acknowledgeResult(id: String) = localVideos.update(id) { it.copy(resultSeen = true) }

    /** Stores the match name and description typed into the details sheet. */
    fun setDetails(id: String, title: String, description: String) =
        localVideos.setDetails(id, normalizeTitle(title), normalizeDescription(description))
}

internal fun LocalVideoEntry.toRow(progress: AnalyzeProgress?, device: LocalAnalysisState): LocalVideoRow {
    // Shared, not written out here: the Analytics list draws a row for this same
    // video one tap away and has to say the same words. Failures are surfaced
    // via a result dialog rather than inline card text, which is why the shared
    // rule returns null for FAILED.
    val cloudStatus = cloudAnalysisStatus(stage, progress)
    val deviceWork = toDeviceWork(id, device)
    val deviceStatus = when {
        deviceWork == null -> null
        // A device failure has no dialog of its own: the result dialog is gated
        // on stage == FAILED, and a device run never moves the stage, so the row
        // is the only place a user learns this broke.
        device is LocalAnalysisState.Failed -> "Analysis failed: ${device.message}"
        // The words the chrome indicator uses, with this list's own ellipsis.
        // Without its percentage: the drawer leaves this line about 115dp, and
        // "Analyzing on device" fills that on its own - appending a number
        // truncated it straight back off ("Analyzing on device ..."), dropping
        // the one part of the label that changes. Home's analysis banner and
        // the chrome indicator both have room for the number and both show it.
        else -> deviceWorkLabel(deviceWork.phase, null) + "…"
    }
    val deviceRunning = isDeviceRunInFlight(device)
    return LocalVideoRow(
        entry = this,
        primaryText = title ?: displayName,
        // The device run speaks over the cloud one, the same precedence
        // affordanceFor uses on the Analytics list: this row's own button starts
        // the device run, so that is the run it has to account for.
        statusText = deviceStatus ?: cloudStatus,
        durationText = formatDuration(durationMs),
        canAnalyze = (stage == AnalyzeStage.LOCAL || stage == AnalyzeStage.FAILED) && !deviceRunning,
        analyzeLabel = analyzeButtonLabel(stage),
        // A device run decodes the file for minutes after the screen that
        // started it is gone; removing the entry mid-run deletes the copy out
        // from under it, exactly as it would under an upload.
        canRemove = canRemoveLocalVideo(stage) && !deviceRunning,
        canEditDetails = canEditLocalVideoDetails(stage),
        isBusy = isAnalysisRunning(stage) || deviceRunning,
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

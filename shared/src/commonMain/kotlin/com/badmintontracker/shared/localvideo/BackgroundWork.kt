package com.badmintontracker.shared.localvideo

import kotlin.math.roundToInt

/**
 * One on-device run, reduced to what an indicator needs.
 *
 * The device pipeline's own state type lives in androidApp rather than here, so
 * [backgroundWork] cannot see it. Reducing to this at the boundary is what lets
 * the merge rules live in shared code and be tested here, instead of being
 * written twice and drifting.
 */
data class DeviceWork(
    val entryId: String,
    val phase: DevicePhase,
    val fraction: Float?,
    val failed: Boolean,
)

/**
 * Which part of an on-device run is happening.
 *
 * Named rather than collapsed into one "analysing" because the indicator's job
 * is to say what is happening: copying a multi-gigabyte video and running
 * inference over it are minutes apart in what the user should expect next.
 */
enum class DevicePhase { PREPARING, ANALYSING, CUTTING }

/** What the chrome indicator renders. Absent, rather than idle, when nothing runs. */
data class BackgroundWork(
    val activeCount: Int,
    val label: String,
    /** `null` renders indeterminate: either unknown, or several runs at once. */
    val fraction: Float?,
    val hasFailure: Boolean,
)

/**
 * Merges the cloud and on-device pipelines into one thing to show.
 *
 * [sessionFailures] holds entry ids that failed while this process has been
 * running, and is the only source of [BackgroundWork.hasFailure]. A persisted
 * `stage == FAILED` deliberately does not count: it survives restarts and is
 * cleared only by a retry, so badging it would light the indicator forever for
 * a video that failed last week. That failure is already on its row, where the
 * Retry that resolves it lives.
 *
 * The caller is responsible for dropping an id once its failure is resolved.
 * A set that only grows is the same permanent-badge bug one layer up: a user
 * who retries and succeeds would be left with a red dot and nothing to click.
 */
fun backgroundWork(
    entries: List<LocalVideoEntry>,
    progress: Map<String, AnalyzeProgress>,
    device: List<DeviceWork>,
    sessionFailures: Set<String>,
): BackgroundWork? {
    val cloud = entries.filter { isAnalysisRunning(it.stage) }
    val running = device.filter { !it.failed }
    val count = cloud.size + running.size
    val hasFailure = sessionFailures.isNotEmpty()

    if (count == 0) {
        // A failure with nothing left running is still worth a badge; it is the
        // only trace the user gets if they were on another screen when it broke.
        return if (hasFailure) BackgroundWork(0, "Analysis failed", null, true) else null
    }

    if (count == 1) {
        val single = cloud.firstOrNull()?.let { entry ->
            val p = progress[entry.id]
            val fraction = p?.pipelineProgress ?: p?.uploadProgress
            when (entry.stage) {
                // Named apart because they are not the same promise: an upload
                // stops when the app leaves the foreground, while Modal keeps
                // going whatever the phone does.
                AnalyzeStage.UPLOADING -> "Uploading" to fraction
                else -> "Processing in the cloud" to fraction
            }
        } ?: running.first().let { device ->
            when (device.phase) {
                DevicePhase.PREPARING -> "Preparing video"
                DevicePhase.ANALYSING -> "Analysing on device"
                DevicePhase.CUTTING -> "Cutting clips"
            } to device.fraction
        }

        return BackgroundWork(1, withPercent(single.first, single.second), single.second, hasFailure)
    }

    // Not averaged: two runs at different stages produce a number that means
    // nothing. Counting analyses rather than videos, because running both
    // pipelines over one video is the comparison this app exists to make.
    return BackgroundWork(count, "$count analyses in progress", null, hasFailure)
}

private fun withPercent(label: String, fraction: Float?): String =
    if (fraction == null || fraction.isNaN()) label
    else "$label ${(fraction.coerceIn(0f, 1f) * 100).roundToInt()}%"

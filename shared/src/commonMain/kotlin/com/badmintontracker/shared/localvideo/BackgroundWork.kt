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
 * Named rather than collapsed into one "analyzing" because the indicator's job
 * is to say what is happening: copying a multi-gigabyte video and running
 * inference over it are minutes apart in what the user should expect next.
 */
enum class DevicePhase { PREPARING, ANALYSING, CUTTING }

/** One thing that is running, as a person would describe it. */
data class BackgroundWorkItem(val label: String, val fraction: Float?)

/** What the chrome indicator renders. Absent, rather than idle, when nothing runs. */
data class BackgroundWork(
    val activeCount: Int,
    val label: String,
    /** `null` renders indeterminate: either unknown, or several runs at once. */
    val fraction: Float?,
    val hasFailure: Boolean,
    /**
     * Every run, kept alongside the summary.
     *
     * The summary collapses to "2 analyses in progress", which is right for a
     * 26dp ring and useless for answering "what is it doing". One long silent
     * spin covers copying the video, building a background plate and running
     * inference, and those are different enough that a user asking is owed the
     * difference.
     */
    val items: List<BackgroundWorkItem> = emptyList(),
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
        return if (hasFailure) {
            BackgroundWork(
                activeCount = 0,
                label = "Analysis failed",
                fraction = null,
                hasFailure = true,
                items = listOf(BackgroundWorkItem("Analysis failed", null)),
            )
        } else {
            null
        }
    }

    val items = buildList {
        cloud.forEach { entry ->
            val p = progress[entry.id]
            val fraction = p?.pipelineProgress ?: p?.uploadProgress
            // Named apart because they are not the same promise: an upload
            // stops when the app leaves the foreground, while Modal keeps
            // going whatever the phone does.
            val base = when (entry.stage) {
                AnalyzeStage.UPLOADING -> "Uploading"
                else -> "Processing in the cloud"
            }
            add(BackgroundWorkItem(withPercent(base, fraction), fraction))
        }
        running.forEach { device ->
            add(BackgroundWorkItem(deviceWorkLabel(device.phase, device.fraction), device.fraction))
        }
    }

    if (count == 1) {
        val single = items.first()
        return BackgroundWork(1, single.label, single.fraction, hasFailure, items)
    }

    // Not averaged: two runs at different stages produce a number that means
    // nothing. Counting analyses rather than videos, because running both
    // pipelines over one video is the comparison this app exists to make.
    return BackgroundWork(count, "$count analyses in progress", null, hasFailure, items)
}

/**
 * How an on-device run reads to a person, as one line.
 *
 * Public because the chrome indicator is not the only thing that has to say
 * this: a list row over a video being analysed has to say the same words, and
 * saying them twice is how the two drift apart.
 */
fun deviceWorkLabel(phase: DevicePhase, fraction: Float?): String = withPercent(
    when (phase) {
        DevicePhase.PREPARING -> "Preparing video"
        DevicePhase.ANALYSING -> "Analyzing on device"
        DevicePhase.CUTTING -> "Cutting clips"
    },
    fraction,
)

private fun withPercent(label: String, fraction: Float?): String =
    if (fraction == null || fraction.isNaN()) label
    else "$label ${(fraction.coerceIn(0f, 1f) * 100).roundToInt()}%"

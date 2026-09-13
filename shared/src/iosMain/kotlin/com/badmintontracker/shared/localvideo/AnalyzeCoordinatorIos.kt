package com.badmintontracker.shared.localvideo

import com.badmintontracker.shared.RallyApp
import com.badmintontracker.shared.local.CloudPoseOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * iOS composition helper: wires the shared AnalyzeCoordinator with a
 * file-streaming channel (entry.uri is a Documents-relative path on iOS).
 */
fun createIosAnalyzeCoordinator(
    rally: RallyApp,
    documentsPath: String,
    /**
     * Swift's sink for a fetched cloud analysis: the stores are Swift types,
     * so the platform decides where an artifact lands. Throwing from here is
     * caught by the coordinator and logged - a heatmap that could not be
     * stored is not a Phase 1 failure.
     */
    saveCloudAnalysis: (entryId: String, outcome: CloudPoseOutcome) -> Unit = { _, _ -> },
): AnalyzeCoordinator =
    rally.analyzeCoordinator(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        openChannel = { uri, offset ->
            openLocalVideoChannel("$documentsPath/$uri", offset)
        },
        log = { println("AnalyzeCoordinator: $it") },
        installCloudPoses = { videoId, onProgress ->
            // Already on Dispatchers.Default via the scope above, so the
            // decode and the selection are off the main thread as the design
            // requires.
            rally.cloudPoseCoordinator(log = { println("CloudPose: $it") })
                .install(videoId, onProgress) { outcome -> saveCloudAnalysis(videoId, outcome) }
                .getOrThrow()
        },
    )

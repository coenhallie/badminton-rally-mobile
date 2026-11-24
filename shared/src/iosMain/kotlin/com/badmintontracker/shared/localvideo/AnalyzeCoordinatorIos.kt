package com.badmintontracker.shared.localvideo

import com.badmintontracker.shared.RallyApp
import com.badmintontracker.shared.invites.PlayerInviteCopy
import com.badmintontracker.shared.local.CloudPoseOutcome
import kotlinx.cinterop.BetaInteropApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.stringByStandardizingPath

/**
 * Resolves a Documents-relative [uri] under [documentsPath], rejecting any
 * absolute, blank, or traversal path that could escape the documents root.
 */
@OptIn(BetaInteropApi::class)
internal fun resolveDocumentsVideoPath(documentsPath: String, uri: String): String {
    require(documentsPath.isNotBlank()) { "documentsPath must not be blank" }
    require(uri.isNotBlank()) { "Video path must not be blank" }
    require('\u0000' !in uri) { "Video path must not contain null bytes" }
    require(!uri.startsWith("/")) { "Video path must be relative: $uri" }
    require(uri.split('/').none { it == ".." }) { "Video path must not contain '..': $uri" }

    val base = NSString.create(string = documentsPath).stringByStandardizingPath.trimEnd('/')
    val candidate = NSString.create(string = "$base/$uri").stringByStandardizingPath
    val prefix = if (base.isEmpty()) "/" else "$base/"
    require(candidate.startsWith(prefix) && candidate.length > prefix.length) {
        "Video path escapes documents directory: $uri"
    }
    return candidate
}

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
            openLocalVideoChannel(resolveDocumentsVideoPath(documentsPath, uri), offset)
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

/**
 * On-demand cloud pose downloader for iOS `AnalysisSection` (linked players and
 * second phones): downloads & decodes off the main thread, invokes [saveCloudAnalysis],
 * and returns a user-facing error message or null on success.
 */
suspend fun installCloudPoseOrMessage(
    rally: RallyApp,
    videoId: String,
    onProgress: (Float) -> Unit,
    saveCloudAnalysis: (entryId: String, outcome: CloudPoseOutcome) -> Unit,
): String? {
    val result = withContext(Dispatchers.Default) {
        rally.cloudPoseCoordinator(log = { println("CloudPose: $it") })
            .install(videoId, onProgress) { outcome -> saveCloudAnalysis(videoId, outcome) }
    }
    return result.exceptionOrNull()?.let { PlayerInviteCopy.ANALYSIS_DOWNLOAD_FAILED }
}

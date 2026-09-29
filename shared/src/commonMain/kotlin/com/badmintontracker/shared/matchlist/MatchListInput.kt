package com.badmintontracker.shared.matchlist

import com.badmintontracker.shared.localvideo.AnalyzeProgress
import com.badmintontracker.shared.localvideo.DevicePhase
import com.badmintontracker.shared.localvideo.LocalAnnotation
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.model.MatchMetadata
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.scoring.ScoreLog

enum class DeviceRunKind { RUNNING, PAUSED, FAILED }

/**
 * An on-device run, reduced to what a row needs. Absent from the map when the
 * run is idle or finished: a finished run speaks through the files it left.
 *
 * Each platform owns its own run-state type (Android's LocalAnalysisState, iOS's
 * own, which alone can be PAUSED), so they meet here.
 */
data class DeviceRun(
    val kind: DeviceRunKind,
    val phase: DevicePhase = DevicePhase.ANALYSING,
    /** The failure or pause reason. */
    val message: String? = null,
)

/**
 * Everything the list is built from. Every field is something Swift can hand
 * over as-is: collections of shared types, strings and ints.
 */
data class MatchListInput(
    val clips: List<RallyClip>,
    val currentUserId: String?,
    val sharerByVideoId: Map<String, String>,
    val metadataByVideoId: Map<String, MatchMetadata>,
    val scoreLogs: List<ScoreLog>,
    val localEntries: List<LocalVideoEntry>,
    val cloudProgress: Map<String, AnalyzeProgress>,
    val deviceRuns: Map<String, DeviceRun>,
    /** Ids (entry or video) with a stored player track: the heatmap. */
    val storedTrackIds: Set<String>,
    /** Ids with a stored skeleton. */
    val storedSkeletonIds: Set<String>,
    /** Rallies an on-device run cut, per entry id. Absent means none. */
    val deviceClipCounts: Map<String, Int>,
    /** Notes made on this phone's copy of a video, per entry id. Absent means none. See [localNoteCounts]. */
    val localNoteCounts: Map<String, Int>,
)

/**
 * [MatchListInput.localNoteCounts] from the phone's note store
 * (`LocalAnnotationsRepository.byVideoId`, keyed by entry id), so both
 * platforms count the same way. A video whose notes were all deleted keeps an
 * empty list there; it is left out, as "absent means none" promises.
 */
fun localNoteCounts(byVideoId: Map<String, List<LocalAnnotation>>): Map<String, Int> =
    byVideoId.mapValues { it.value.size }.filterValues { it > 0 }

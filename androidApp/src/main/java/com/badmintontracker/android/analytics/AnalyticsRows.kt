package com.badmintontracker.android.analytics

import com.badmintontracker.android.cliplist.MatchRow
import com.badmintontracker.android.cliplist.MatchSummary
import com.badmintontracker.android.cliplist.formatDate
import com.badmintontracker.android.cliplist.matchRowPrimary
import com.badmintontracker.android.cliplist.matchRowSecondary
import com.badmintontracker.android.localanalysis.LocalAnalysisState
import com.badmintontracker.android.localvideo.LocalVideoRow
import com.badmintontracker.shared.analytics.AnalyticsRowState
import com.badmintontracker.shared.analytics.analyticsRowState
import com.badmintontracker.shared.localvideo.AnalyzeProgress
import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.DevicePhase
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.cloudAnalysisStatus
import com.badmintontracker.shared.localvideo.deviceWorkLabel
import com.badmintontracker.shared.localvideo.isAnalysisRunning
import kotlinx.datetime.Instant

/**
 * Which of the two pipelines a row's control should speak for.
 *
 * Two independent pipelines can be running over the same video at once (see
 * BackgroundWork.kt's own comment on why cloud and on-device failures are
 * tracked apart). The device run wins when both are non-idle, because that is
 * the run this row's own button would start, but the cloud pipeline must not be
 * left silent just because the button starts the other one: a coach watching
 * this list must not see "Analyze" on a video that is, in fact, being analyzed
 * right now, in the cloud.
 */
internal fun affordanceFor(
    entry: LocalVideoEntry,
    live: LocalAnalysisState,
    progress: AnalyzeProgress?,
): AnalyseAffordance = when (live) {
    // Through [deviceWorkLabel] rather than three literals of its own, which is
    // what these used to be: the drawer's row and the chrome indicator already
    // go through it, and a fourth hand-written copy is how this screen kept a
    // spelling the rest of the app had left behind. No fraction, matching the
    // drawer's row - see its comment for why the number is left off a list line.
    is LocalAnalysisState.Preparing -> AnalyseAffordance.InProgress(deviceWorkLabel(DevicePhase.PREPARING, null))
    is LocalAnalysisState.Analysing -> AnalyseAffordance.InProgress(deviceWorkLabel(DevicePhase.ANALYSING, null))
    is LocalAnalysisState.Cutting -> AnalyseAffordance.InProgress(deviceWorkLabel(DevicePhase.CUTTING, null))
    is LocalAnalysisState.Failed -> AnalyseAffordance.Failed(live.message)
    // No device run in flight or failed: fall back to the cloud pipeline's own
    // liveness for this entry, in the shared wording the drawer's row for this
    // same video uses. It used to say "Uploading" and "Processing in the cloud"
    // here, which is the chrome indicator's vocabulary and dropped the
    // percentage - so one tap apart the same run read two ways, and iOS's port
    // of this screen had already gone the other way.
    is LocalAnalysisState.Idle, is LocalAnalysisState.Done -> when {
        // Non-null for both running stages, so the fallback guards a future
        // stage rather than anything reachable today.
        isAnalysisRunning(entry.stage) ->
            AnalyseAffordance.InProgress(cloudAnalysisStatus(entry.stage, progress) ?: "Analyzing…")
        entry.stage == AnalyzeStage.FAILED -> AnalyseAffordance.Failed(entry.failureMessage ?: "Unknown error")
        // A track only lands on disk when the run asked for pose (see
        // LocalAnalysisRunner.start): a Done device run that did not is not a
        // liveness problem, just a video still waiting for its first (pose)
        // analysis - the same as one that was never touched.
        else -> AnalyseAffordance.Ready
    }
}

/**
 * Which explanatory line sits above the list, if any.
 *
 * The screen used to switch two ways: all-inert, or the dot legend. That put
 * "Analyzed on this phone - tap to view" and a green dot above a list where the
 * only row said "Analyze" and no dot was drawn anywhere, because ANALYSABLE
 * fails the all-inert test without contributing a dot. A legend explaining a
 * symbol that is not on screen is worse than no legend. Found on iOS, where
 * every local video is ANALYSABLE and it was the ONLY thing a coach would see.
 *
 * A dot is a symbol and needs explaining; a button already says what it does,
 * so the dot wins whenever both are present.
 */
internal enum class AnalyticsLegend {
    SILENT, NOTHING_ON_THIS_PHONE, DOT, ANALYSE_BUTTON;

    /** Whether any row draws the availability dot the [DOT] line explains. */
    val showsDot: Boolean get() = this == DOT

    /**
     * Whether an inert row spells out "Not on this phone" for itself.
     *
     * False only when the legend already said it once for the whole list. Kept
     * here rather than in the view so the legend and the rows cannot disagree
     * about which of them is doing the explaining.
     */
    val showsNotOnDeviceSubtitle: Boolean get() = this != NOTHING_ON_THIS_PHONE
}

internal fun analyticsLegend(rows: List<AnalyticsRow>): AnalyticsLegend = when {
    rows.isEmpty() -> AnalyticsLegend.SILENT
    // Repeating "Not on this phone" down the whole list would say the same thing
    // as many times as there are rows.
    rows.all { it.state == AnalyticsRowState.NOT_ON_DEVICE } -> AnalyticsLegend.NOTHING_ON_THIS_PHONE
    rows.any { it.state == AnalyticsRowState.READY } -> AnalyticsLegend.DOT
    else -> AnalyticsLegend.ANALYSE_BUTTON
}

/**
 * Builds the Analytics list's rows, grouped like the drawer: local videos, then
 * owned matches, then shared. [analyticsRowState] alone decides READY /
 * ANALYSABLE / NOT_ON_DEVICE - this only gathers its two booleans per match
 * and, for an ANALYSABLE one, reads both pipelines' own liveness via
 * [affordanceFor] so the row can show progress or a failure instead of a live
 * "Analyze" button. `hasStoredTrack` alone cannot tell a run in flight, or one
 * that just failed, from one never attempted.
 *
 * [storedTrackIds] is a caller-computed set rather than a per-row disk probe:
 * this function runs on every recomposition, including the several-times-a-
 * second ones an in-flight analysis's progress causes.
 */
internal fun buildAnalyticsRows(
    standaloneLocalRows: List<LocalVideoRow>,
    ownedRows: List<MatchRow>,
    sharedMatches: List<MatchSummary>,
    localEntries: List<LocalVideoEntry>,
    liveAnalysisStates: Map<String, LocalAnalysisState>,
    progressByEntryId: Map<String, AnalyzeProgress>,
    storedTrackIds: Set<String>,
): List<AnalyticsRow> {
    fun rowFor(key: String, entryId: String?, group: AnalyticsGroup, title: String, subtitle: String): AnalyticsRow {
        val entry = entryId?.let { id -> localEntries.firstOrNull { it.id == id } }
        val hasStoredTrack = entryId != null && entryId in storedTrackIds
        val state = analyticsRowState(hasLocalEntry = entry != null, hasStoredTrack = hasStoredTrack)
        // Only an ANALYSABLE row reads liveness, and that is a decision rather
        // than an oversight. A READY row whose second run is in flight keeps its
        // dot and stays tappable, because the track the first run produced still
        // opens - swapping the dot for a spinner would take away a working
        // affordance to report a run whose result the row does not need yet.
        // LocalVideoRowItem spins whenever isAnalysisRunning; it has no stored
        // result to offer instead, so it has nothing to lose by doing that.
        //
        // entry is never null here: ANALYSABLE requires hasLocalEntry, which is
        // exactly `entry != null` above. The null check stays as a guard, not a
        // second source of truth, so this cannot throw if that ever changes.
        val affordance = if (state == AnalyticsRowState.ANALYSABLE && entry != null) {
            affordanceFor(
                entry,
                liveAnalysisStates[entry.id] ?: LocalAnalysisState.Idle,
                progressByEntryId[entry.id],
            )
        } else {
            AnalyseAffordance.Ready
        }
        return AnalyticsRow(
            key = key,
            // Not on this phone and inert either way once there is no entry,
            // so there is nothing for a null id to navigate to.
            entryId = entry?.id,
            group = group,
            title = title,
            subtitle = subtitle,
            state = state,
            affordance = affordance,
        )
    }

    val localVideoRows = standaloneLocalRows.map { row ->
        rowFor(
            key = "local-${row.entry.id}",
            entryId = row.entry.id,
            group = AnalyticsGroup.LOCAL_VIDEOS,
            title = row.primaryText,
            // Sentence case, as the mock has it ("1:05 · Sep 2"). This screen
            // used to uppercase its match rows and not its local video rows, so
            // one list drew "0:02 · Sep 4, 2026" directly over
            // "2 RALLIES · SEP 1, 2026"; the answer turned out to be neither.
            subtitle = "${row.durationText} · " +
                formatDate(Instant.fromEpochMilliseconds(row.entry.addedAtEpochMs)),
        )
    }

    val ownedMatchRows = ownedRows.map { row ->
        when (row) {
            is MatchRow.Video -> rowFor(
                key = row.key,
                entryId = row.match.videoId,
                group = AnalyticsGroup.OWNED_MATCHES,
                title = matchRowPrimary(row.match),
                subtitle = matchRowSecondary(row.match),
            )
            is MatchRow.Score -> rowFor(
                key = row.key,
                // The entry that claims this log, which is how the rest of the
                // app pairs a scored match to its video (scoreLogAttachStatus
                // in AttachStatus.kt, and both of AuthGate's attached-video
                // lookups). Not `card.videoId`: that is written only by the
                // CLOUD pipeline's CREATE_ROW hook, so a match scored courtside
                // and filmed on this phone has none, and reading it would call
                // a video sitting on the phone "Not on this phone" forever.
                entryId = localEntries.firstOrNull { it.scoreLogId == row.card.scoreLogId }?.id,
                group = AnalyticsGroup.OWNED_MATCHES,
                title = row.card.title,
                subtitle = row.card.playersLine,
            )
        }
    }

    val sharedRows = sharedMatches.map { match ->
        rowFor(
            key = "shared-${match.videoId}",
            entryId = match.videoId,
            group = AnalyticsGroup.SHARED,
            title = matchRowPrimary(match),
            subtitle = matchRowSecondary(match),
        )
    }

    // One video, one row. A video-first import the cloud has finished clipping
    // is both a local video and an owned match, and the two rows carry the same
    // entry id, the same state and two identical "Analyze" buttons - the drawer
    // gets away with that because its two rows look nothing alike, and here they
    // are indistinguishable. The local row wins because it is the stable one: it
    // exists from import until the file leaves the phone, whereas the owned row
    // appears only once a cloud run finishes, so keeping that one instead would
    // make the row jump sections mid-life. Rows with no entry fall back to their
    // own key, and every NOT_ON_DEVICE row has a null entryId, so two matches
    // that are merely both absent from this phone never collapse into each other.
    return (localVideoRows + ownedMatchRows + sharedRows).distinctBy { it.entryId ?: it.key }
}

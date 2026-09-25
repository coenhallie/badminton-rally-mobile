package com.badmintontracker.android.analytics

import com.badmintontracker.android.localanalysis.LocalAnalysisState
import com.badmintontracker.shared.analytics.AnalyticsRowState
import com.badmintontracker.shared.analytics.analyticsRowState
import com.badmintontracker.shared.analytics.opensAnalytics
import com.badmintontracker.shared.localvideo.AnalyzeProgress
import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.DevicePhase
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.cloudAnalysisStatus
import com.badmintontracker.shared.localvideo.deviceWorkLabel
import com.badmintontracker.shared.localvideo.isAnalysisRunning
import com.badmintontracker.shared.matchlist.MatchList
import com.badmintontracker.shared.matchlist.MatchListItem

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
    rows.any { opensAnalytics(it.state) } -> AnalyticsLegend.DOT
    else -> AnalyticsLegend.ANALYSE_BUTTON
}

/**
 * Builds the Analytics list's rows: one per [MatchListItem] in [matchList],
 * the coach's own matches first and shared ones after - the same rows, in the
 * same order, the drawer draws, because both read the one [MatchList] the
 * shared builder produces from the same inputs.
 *
 * A video-first import the cloud has finished clipping used to reach this
 * screen as two candidate rows - a standalone local video and an owned match -
 * that this function then picked one of by a `distinctBy` on entry id. The
 * shared builder now folds those into a single [MatchListItem] before this
 * ever runs (see `MatchListBuilder.videoItem`), so there are never two
 * candidates here to choose between.
 *
 * [analyticsRowState] alone decides READY / READY_NO_VIDEO / ANALYSABLE /
 * NOT_ON_DEVICE, from the same two facts the item already carries - whether it
 * has a local entry ([MatchListItem.entryId]) and whether it has a stored
 * track ([MatchListItem.heatmapId]) - so this function only re-reads those two
 * fields. For an ANALYSABLE row it also reads both pipelines' own liveness via
 * [affordanceFor], so the row's control can show progress or a failure instead
 * of a live "Analyze" label; `hasStoredTrack` alone cannot tell a run in
 * flight, or one that just failed, from one never attempted, and that is the
 * one distinction [MatchListItem.action] does not carry a matching affordance
 * for. A READY row's own liveness is not lost by skipping it here: it already
 * reaches the screen through the item's own `action`/`statusText`, which
 * [com.badmintontracker.android.cliplist.MatchStatusLine] draws for every row,
 * the same component the drawer uses for the same fields.
 */
internal fun buildAnalyticsRows(
    matchList: MatchList,
    localEntries: List<LocalVideoEntry>,
    liveAnalysisStates: Map<String, LocalAnalysisState>,
    progressByEntryId: Map<String, AnalyzeProgress>,
): List<AnalyticsRow> {
    fun rowFor(item: MatchListItem, group: AnalyticsGroup): AnalyticsRow {
        val entry = item.entryId?.let { id -> localEntries.firstOrNull { it.id == id } }
        val trackId = item.heatmapId
        val state = analyticsRowState(hasLocalEntry = item.entryId != null, hasStoredTrack = trackId != null)
        // entry is never null here when state is ANALYSABLE: ANALYSABLE
        // requires hasLocalEntry, which is exactly `item.entryId != null`
        // above, and entry is looked up by that same id. The null check stays
        // as a guard, not a second source of truth, so this cannot throw if
        // that relationship ever changes.
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
            key = item.key,
            // The track's own id stands in once there is no local entry to
            // open instead: AnalyticsRowState.READY_NO_VIDEO is exactly this
            // case, and this row still has to open on a tap.
            entryId = item.entryId ?: trackId,
            group = group,
            title = item.title,
            subtitle = item.detail,
            state = state,
            affordance = affordance,
            item = item,
        )
    }

    return matchList.mine.map { rowFor(it, AnalyticsGroup.MINE) } +
        matchList.shared.map { rowFor(it, AnalyticsGroup.SHARED) }
}

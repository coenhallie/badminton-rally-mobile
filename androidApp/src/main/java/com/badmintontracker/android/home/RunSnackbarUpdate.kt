package com.badmintontracker.android.home

import com.badmintontracker.android.localanalysis.LocalAnalysisState
import com.badmintontracker.shared.localvideo.announceFinishedRuns

/**
 * The runs of both pipelines that count as finished, for the announcement on
 * the match list's snackbar.
 *
 * A device run that found nothing (no rally, no track, no skeleton) is left
 * out, from its own outcome (`Done.foundSomething`): it is not "analyzed", and
 * its row still offers Analyze. Not decided from the list later, which can lag
 * a run and would lose one for good once its id is marked seen. Cloud runs are
 * not filtered: one that finds no rallies fails instead of finishing, and its
 * results land after the finish. The two sets are unioned so one rule decides,
 * and a video analyzed both ways announces once.
 *
 * Kept out of AuthGate's watcher so it can be tested, as iOS keeps
 * `runToastUpdate` out of HomeView.
 */
fun finishedRuns(deviceStates: Map<String, LocalAnalysisState>, cloudFinished: Set<String>): Set<String> =
    deviceStates.filterValues { it is LocalAnalysisState.Done && it.foundSomething }.keys + cloudFinished

/** What a change in the finished runs does to the list's snackbar. */
data class RunSnackbarUpdate(
    /** Pass back in as `seen` next time. See [announceFinishedRuns]. */
    val seen: Set<String>,
    /**
     * The runs the next snackbar should name, or null when there is nothing
     * new to say and the waiting set stays as it is.
     */
    val announced: Set<String>?,
)

/**
 * AuthGate's announcement rule around the shared seen-set rule: which of the
 * [finished] runs reach the list's snackbar, merged with the runs still
 * [waiting] to be shown.
 *
 * Merged, not replaced: two runs can land on consecutive collections before
 * HomeScreen has read and cleared the first, and a plain assignment dropped
 * it. Once HomeScreen has taken them ([waiting] empty again), the next set
 * starts from the new runs alone, and its snackbar queues behind any still on
 * screen. That is where Android differs from iOS, whose toast takes a run
 * landing while it shows (design 7.5).
 */
fun runSnackbarUpdate(
    finished: Set<String>,
    seen: Set<String>,
    onHome: Boolean,
    waiting: Set<String>,
): RunSnackbarUpdate {
    val announcement = announceFinishedRuns(finished = finished, seen = seen, onHome = onHome)
    if (announcement.announce.isEmpty()) return RunSnackbarUpdate(announcement.seen, null)
    return RunSnackbarUpdate(announcement.seen, waiting + announcement.announce)
}

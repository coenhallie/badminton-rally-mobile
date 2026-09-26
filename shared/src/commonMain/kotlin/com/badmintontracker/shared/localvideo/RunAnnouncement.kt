package com.badmintontracker.shared.localvideo

data class RunAnnouncement(
    /** The runs to announce now: newly finished, and only while the list is in front. */
    val announce: Set<String>,
    /** Pass back into the next [announceFinishedRuns] call. */
    val seen: Set<String>,
) {
    /** Transitional, for the tab-switch callers until Task 10 of the 2026-09-26 plan removes them. */
    val openAnalytics: Boolean get() = announce.isNotEmpty()
}

/**
 * Whether a finished run, on-device or in the cloud, should be announced on
 * the match list.
 *
 * A run takes minutes and the coach does not sit and watch it, which is the
 * whole reason the progress indicator lives in the app bar rather than on one
 * screen. So "it finished" cannot simply navigate: landing on a result while
 * the coach is part-way through labelling a rally takes the app out from under
 * them to show a result they did not ask for at that moment.
 *
 * Two rules, and the interesting one is the second.
 *
 * [onHome] gates the announcement: true only when the list is the front screen
 * with nothing above it (`isHomeFront`). Anywhere else nothing is announced,
 * exactly as it was before this existed.
 *
 * [seen] is updated WHETHER OR NOT the announcement happens, which is what
 * stops a run that finished while the coach was elsewhere from ambushing them
 * the next time they happen to come back to the list. Call this on every state
 * change, from somewhere that is always composed, not from the list itself;
 * called only while the list is on screen it would have no way to tell a run
 * that has just landed from one that landed ten minutes ago.
 *
 * Deliberately `seen = finished` rather than `seen + finished`: a re-analysis
 * of the same video leaves the finished set while it runs and re-enters it when
 * it lands, and the coach asked for that second run too, so it announces again.
 * An accumulating set would announce the first run of each video and then go
 * quiet forever.
 */
fun announceFinishedRuns(
    finished: Set<String>,
    seen: Set<String>,
    onHome: Boolean,
): RunAnnouncement = RunAnnouncement(
    announce = if (onHome) finished - seen else emptySet(),
    seen = finished,
)

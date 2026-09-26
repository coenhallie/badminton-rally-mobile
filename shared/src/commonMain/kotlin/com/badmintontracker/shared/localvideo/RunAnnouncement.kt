package com.badmintontracker.shared.localvideo

/**
 * What to do about runs that have just finished: whether to switch the coach
 * from the Matches tab to the Analytics tab, and what the new seen-set is.
 */
data class RunAnnouncement(
    val openAnalytics: Boolean,
    /** Pass back into the next [announceFinishedRuns] call. */
    val seen: Set<String>,
)

/**
 * Whether a finished run, on-device or in the cloud, should switch the coach
 * from the Matches tab to the Analytics tab.
 *
 * A run takes minutes and the coach does not sit and watch it, which is the
 * whole reason the progress indicator lives in the app bar rather than on one
 * screen. So "it finished" cannot simply navigate: landing on Analytics while
 * the coach is part-way through labelling a rally takes the app out from under
 * them to show a result they did not ask for at that moment.
 *
 * Two rules, and the interesting one is the second.
 *
 * [onHome] gates the switch: true only for the Matches tab with nothing above
 * it (`isHomeFront`). Anywhere else the app bar indicator stays the way back,
 * exactly as it was before this existed.
 *
 * [seen] is updated WHETHER OR NOT the switch happens, which is what stops a
 * run that finished while the coach was elsewhere from ambushing them the next
 * time they happen to come back to the Matches tab. Call this on every state
 * change, from somewhere that is always composed, not from the tab itself;
 * called only while the tab is on screen it would have no way to tell a run
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
    openAnalytics = onHome && (finished - seen).isNotEmpty(),
    seen = finished,
)

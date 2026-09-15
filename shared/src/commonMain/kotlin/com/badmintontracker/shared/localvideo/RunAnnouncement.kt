package com.badmintontracker.shared.localvideo

/**
 * What to do about device runs that have just finished: whether to take the
 * coach to the Analytics list, and what the new seen-set is.
 */
data class RunAnnouncement(
    val openAnalytics: Boolean,
    /** Pass back into the next [announceFinishedRuns] call. */
    val seen: Set<String>,
)

/**
 * Whether a finished on-device run should pull the coach to the Analytics list.
 *
 * A run takes minutes and the coach does not sit and watch it, which is the
 * whole reason the progress indicator lives in the app bar rather than on one
 * screen. So "it finished" cannot simply navigate: landing on Analytics while
 * the coach is part-way through labelling a rally takes the app out from under
 * them to show a result they did not ask for at that moment.
 *
 * Two rules, and the interesting one is the second.
 *
 * [onHome] gates the navigation. Anywhere else the app bar indicator stays the
 * way back, exactly as it was before this existed.
 *
 * [seen] is updated WHETHER OR NOT the navigation happens, which is what stops
 * a run that finished while the coach was elsewhere from ambushing them the
 * next time they happen to pass through Home. Call this on every state change,
 * from somewhere that is always composed, not from Home itself; called only
 * while Home is on screen it would have no way to tell a run that has just
 * landed from one that landed ten minutes ago.
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

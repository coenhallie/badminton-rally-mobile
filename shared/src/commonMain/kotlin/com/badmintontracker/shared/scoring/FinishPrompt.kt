package com.badmintontracker.shared.scoring

/**
 * When the board asks "Add the video?": once per visit, the first time the visit
 * SEES the match stop being scoreable.
 *
 * Seeing the transition is the point. A won match stays LIVE so a mis-tapped last
 * point can be undone, and the match page offers "Resume scoring" for it. A rule
 * that asked whenever the match was un-scoreable put the prompt over the board on
 * arrival, and every way out of the prompt leaves the board, so the undo the LIVE
 * status exists for could never be reached.
 *
 * Shared because both boards carried their own copy of the rule, with the same
 * flaw. One instance per visit to the board; not thread safe, and called from the
 * UI thread only.
 *
 * @param playableOnEntry [isPlayable] as the board opened. False means the coach
 *   came back to a match that is already over, which is not news to them.
 */
class FinishPrompt(playableOnEntry: Boolean) {
    private var asked = !playableOnEntry

    /**
     * Feed it [isPlayable] every time it may have changed. True exactly once,
     * which is the moment to open the prompt.
     */
    fun shouldAsk(isPlayable: Boolean): Boolean {
        if (isPlayable) {
            // Back in play, which only undo can do. A match that was already over
            // on entry is now an ordinary one and earns its question; one that was
            // asked about this visit keeps its answer.
            if (!askedThisVisit) asked = false
            return false
        }
        if (asked) return false
        asked = true
        askedThisVisit = true
        return true
    }

    private var askedThisVisit = false
}

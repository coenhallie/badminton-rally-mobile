package com.badmintontracker.shared.home

/**
 * The three places the bottom bar goes. Shared so both clients name, order and
 * default the tabs the same way; each draws its own bar.
 *
 * The bar lives INSIDE the root destination on both platforms, not around the
 * navigation stack: every pushed screen (the scoring board above all) covers it
 * with no per-screen code. See docs/plans/2026-09-25-bottom-navigation-design.md.
 */
enum class HomeTab {
    MATCHES, ANALYTICS, SETTINGS;

    companion object {
        /** The landing tab. A fresh sign-in lands here, not on the last tab used. */
        val DEFAULT = MATCHES
    }
}

/**
 * What each platform passes as `announceFinishedRuns`'s `onHome`.
 *
 * Matches only: that is the tab the coach watches a run on (its row says
 * "Analyzing 42%"), so a run landing there moves them to Analytics to see it.
 * From Settings it would pull them out of something unrelated, and on
 * Analytics they are already there. [nothingAbove] is each platform's own
 * "no screen pushed, no sheet or dialog open over the tabs".
 */
fun isHomeFront(tab: HomeTab, nothingAbove: Boolean): Boolean =
    nothingAbove && tab == HomeTab.MATCHES

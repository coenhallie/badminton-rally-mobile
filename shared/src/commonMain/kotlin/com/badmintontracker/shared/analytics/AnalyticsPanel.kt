package com.badmintontracker.shared.analytics

/** The tabs an analysed match's detail screen can offer, in the order it offers them. */
enum class AnalyticsPanel(val label: String) {
    Heatmap("Heatmap"),
    Base("Base"),
    Skeleton("Skeleton"),
}

/**
 * Which panels this entry has content for.
 *
 * The heatmap is always offered: it is the screen's own subject and it says
 * for itself when there is no track. The other two are offered only when they
 * would not be empty themselves. Base needs a track with samples and rally
 * windows with bounds, which is what the base position is measured from;
 * skeleton needs a stored skeleton, which only a run with the skeleton metric
 * ticked leaves behind.
 *
 * Shared rather than written per platform: a tab offered on one phone and not
 * the other is the coach seeing two different apps, and the rule is four
 * lines - exactly the size that gets re-derived slightly differently.
 */
fun availablePanels(hasTrack: Boolean, hasBoundedClips: Boolean, hasSkeleton: Boolean): List<AnalyticsPanel> =
    buildList {
        add(AnalyticsPanel.Heatmap)
        if (hasTrack && hasBoundedClips) add(AnalyticsPanel.Base)
        if (hasSkeleton) add(AnalyticsPanel.Skeleton)
    }

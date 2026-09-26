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
 * skeleton needs a stored skeleton AND the video it overlays.
 *
 * [hasVideo] is the newest of the four and the least obvious. A cloud analysis
 * reaches a phone that never held the footage - it was uploaded from another
 * of this coach's phones - and the heatmap and the base position are both
 * computed entirely from the stored track, so they work there. The skeleton is
 * an overlay on playback, and a tab that could only ever say "no video on this
 * phone" is worse than one that is not offered.
 *
 * Shared rather than written per platform: a tab offered on one phone and not
 * the other is the coach seeing two different apps, and the rule is four
 * lines - exactly the size that gets re-derived slightly differently.
 */
fun availablePanels(
    hasTrack: Boolean,
    hasBoundedClips: Boolean,
    hasSkeleton: Boolean,
    hasVideo: Boolean,
): List<AnalyticsPanel> = buildList {
    add(AnalyticsPanel.Heatmap)
    if (hasTrack && hasBoundedClips) add(AnalyticsPanel.Base)
    if (hasSkeleton && hasVideo) add(AnalyticsPanel.Skeleton)
}

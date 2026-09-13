package com.badmintontracker.shared.analytics

/**
 * What a coach can do with one match on the Analytics list.
 *
 * NOT_ON_DEVICE is not a failure. A match with neither footage nor a track has
 * nothing to show and no way to make anything, so offering to analyse it would
 * promise something that cannot run.
 */
enum class AnalyticsRowState {
    /** A stored player track exists and the video is here. Opens every panel it has content for. */
    READY,

    /**
     * A stored track exists but the video does not, and cannot.
     *
     * A cloud analysis of footage uploaded from another phone: the track came
     * down, the file stayed there. Opens the heatmap and the base position,
     * which read only the track; the skeleton is not offered, because it
     * overlays playback.
     */
    READY_NO_VIDEO,

    /** The video is on this phone but has not been analysed yet. */
    ANALYSABLE,

    /** No local video, no track, and no way to get either. Inert. */
    NOT_ON_DEVICE,
}

/**
 * Classifies one match.
 *
 * The track decides whether there is anything to show and the local entry
 * decides whether the footage is here; the two questions used to be one. A
 * track can outlive the video it came from, because a run's result is kept on
 * disk while the file itself can be removed - so a track with no entry is
 * READY_NO_VIDEO rather than READY, and a coach who taps it gets the heatmap
 * rather than a skeleton tab with nothing behind it.
 */
fun analyticsRowState(hasLocalEntry: Boolean, hasStoredTrack: Boolean): AnalyticsRowState = when {
    hasLocalEntry && hasStoredTrack -> AnalyticsRowState.READY
    hasLocalEntry -> AnalyticsRowState.ANALYSABLE
    hasStoredTrack -> AnalyticsRowState.READY_NO_VIDEO
    else -> AnalyticsRowState.NOT_ON_DEVICE
}

/**
 * Whether tapping this row opens something.
 *
 * Shared rather than compared inline at each of the four call sites - two
 * screens on each platform - for the reason [analyticsRowState] itself is
 * shared. This started as `state == READY` written out four times, and the
 * moment READY_NO_VIDEO existed, all four were silently wrong in the same way:
 * a cloud-analysed match would have drawn as an inert row with a heatmap
 * sitting behind it.
 */
fun opensAnalytics(state: AnalyticsRowState): Boolean =
    state == AnalyticsRowState.READY || state == AnalyticsRowState.READY_NO_VIDEO

package com.badmintontracker.shared.analytics

/**
 * What a coach can do with one match on the Analytics list.
 *
 * NOT_ON_DEVICE is not a failure. A match uploaded from another phone, or
 * shared by a coach, has no local video and never can have one, so offering to
 * analyse it would promise something that cannot run.
 */
enum class AnalyticsRowState {
    /** A stored player track exists. Opens the heatmap. */
    READY,

    /** The video is on this phone but has not been analysed yet. */
    ANALYSABLE,

    /** No local video, and no way to get one. Inert. */
    NOT_ON_DEVICE,
}

/**
 * Classifies one match.
 *
 * The local entry decides, not the track: a track can outlive the video it came
 * from, because a run's result is kept on disk while the file itself can be
 * removed. Treating that as READY would send the coach to a heatmap whose match
 * is no longer on this phone.
 */
fun analyticsRowState(hasLocalEntry: Boolean, hasStoredTrack: Boolean): AnalyticsRowState = when {
    !hasLocalEntry -> AnalyticsRowState.NOT_ON_DEVICE
    hasStoredTrack -> AnalyticsRowState.READY
    else -> AnalyticsRowState.ANALYSABLE
}

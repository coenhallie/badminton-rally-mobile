package com.badmintontracker.android.nav

import kotlinx.serialization.Serializable

sealed interface Route {
    @Serializable data object SignIn : Route
    @Serializable data object Home   : Route
    @Serializable data object Labels : Route

    /** The coach's Analytics list: one row per match, grouped like the drawer. */
    @Serializable data object Analytics : Route

    /**
     * What one analysed match has to show, reached from a READY row on the
     * Analytics list. Keyed by entry for the same reason [Heatmap] is.
     *
     * Separate from [Heatmap] rather than replacing it: that route is the
     * analysis banner's own destination and shows the heatmap alone, while this
     * one is the list's destination and carries the tab row. Both draw their
     * content with the same HeatmapPanel.
     */
    @Serializable data class AnalyticsDetail(val entryId: String) : Route

    /**
     * One match, however it was made. At least one of the two ids is non-null: a
     * video-first or shared match has only a video, a scored match has a score log
     * and gains a video later.
     *
     * [attach] carries the intent chosen on the board's "add the video?" prompt
     * ("Import" or "Record"), null otherwise. It is read once by the match page and
     * not part of route identity beyond that: because it can differ between two
     * Route.Match values for the same match, nothing may pop or popUpTo a
     * Route.Match by reconstructing one - only the instance already on the stack.
     */
    @Serializable data class Match(
        val scoreLogId: String? = null,
        val videoId: String? = null,
        val attach: String? = null,
    ) : Route

    @Serializable data class  ClipDetail(val clipId: String)  : Route
    @Serializable data class  LocalPlayer(val entryId: String)  : Route
    @Serializable data class  CourtMarking(val entryId: String) : Route

    /**
     * The court heatmap for one on-device run.
     *
     * Keyed by entry rather than carrying the track: a track is thousands of
     * points and navigation arguments are serialized into the back stack.
     */
    @Serializable data class  Heatmap(val entryId: String) : Route

    /** Clips a device run cut, which never leave the phone. */
    @Serializable data class  LocalClips(val entryId: String) : Route
    @Serializable data object NewMatch : Route
    @Serializable data class  Scoring(val scoreLogId: String)    : Route
}

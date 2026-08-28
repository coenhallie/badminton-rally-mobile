package com.badmintontracker.android.nav

import kotlinx.serialization.Serializable

sealed interface Route {
    @Serializable data object SignIn   : Route
    @Serializable data object ClipList : Route
    @Serializable data object Labels   : Route

    /**
     * One match, however it was made. At least one of the two ids is non-null: a
     * video-first or shared match has only a video, a scored match has a score log
     * and gains a video later.
     */
    @Serializable data class Match(
        val scoreLogId: String? = null,
        val videoId: String? = null,
    ) : Route

    @Serializable data class  ClipDetail(val clipId: String)  : Route
    @Serializable data class  LocalPlayer(val entryId: String)  : Route
    @Serializable data class  CourtMarking(val entryId: String) : Route
    @Serializable data object NewMatch : Route
    @Serializable data class  Scoring(val scoreLogId: String)    : Route
}

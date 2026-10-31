package com.badmintontracker.android.nav

import kotlinx.serialization.Serializable

sealed interface Route {
    @Serializable data object SignIn : Route
    /** The start destination: the coach's players, and the way to All matches. */
    @Serializable data object Players : Route
    /** One player's page. */
    @Serializable data class Player(val playerId: String) : Route
    /** Every match, assigned to a player or not: the match list, pushed from Players. */
    @Serializable data object AllMatches : Route
    @Serializable data object Labels : Route
    /** Pushed from the gear on the Players title bar. */
    @Serializable data object Settings : Route

    /**
     * One match, however it was made. At least one of the three ids is
     * non-null: a video-first or shared match has a video, a scored match has a
     * score log and may gain a video later, and a video only on this phone has
     * neither, so [entryId] is how that one is opened. The page finds its row in
     * the match list by whichever id the list still has (matchPageItem).
     *
     * [attach] carries the intent chosen on the board's "add the video?" prompt
     * ("Import" or "Record"), null otherwise. It is read once by the match page and
     * not part of route identity beyond that: because it can differ between two
     * Route.Match values for the same match, nothing may pop or popUpTo a
     * Route.Match by reconstructing one - only the instance already on the stack.
     *
     * [section] is a MatchPageSection name to open on (a finished run's View),
     * null for the page's default. Read once when the page opens, like [attach],
     * and under the same rule about reconstructing a Route.Match.
     */
    @Serializable data class Match(
        val scoreLogId: String? = null,
        val videoId: String? = null,
        val entryId: String? = null,
        val attach: String? = null,
        val section: String? = null,
    ) : Route

    @Serializable data class  ClipDetail(val clipId: String)  : Route
    @Serializable data class  CourtMarking(val entryId: String) : Route
    /**
     * [playerId] is the player the match is started for (their page's add
     * button), null from anywhere else. Pop it by type (popUpTo<NewMatch>), never
     * by a reconstructed instance, which would carry a different player.
     */
    @Serializable data class  NewMatch(val playerId: String? = null) : Route
    @Serializable data class  Scoring(val scoreLogId: String)    : Route
}

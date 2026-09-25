package com.badmintontracker.shared.matchlist

import com.badmintontracker.shared.model.RallyClip

/** Where a match's video is. Null on an item means it has none: a match scored with no video. */
enum class MatchWhere { PHONE, CLOUD, BOTH }

/**
 * What analysis a match has, in the order the status line draws it.
 *
 * Only what exists is ever listed. An item with nothing says so in words
 * ("Not analyzed") rather than drawing four empty glyphs.
 */
enum class MatchAnalysis { RALLIES, HEATMAP, SKELETON, SCORE }

/**
 * The one thing a row asks of the coach, if anything. Any value but NONE makes
 * the row the drawer's raised card.
 */
enum class MatchAction { NONE, ANALYZE, MARK_COURT, RETRY, RUNNING, PAUSED }

/** What "Delete" does on this row, before the phone copy is considered. */
enum class MatchDelete { NONE, VIDEO_MATCH, SCORE_MATCH, BOUND_MATCH, LEAVE_SHARE }

/**
 * One match in the drawer and on the Analytics picker.
 *
 * Plain fields and enums rather than sealed types on purpose: Swift reads this
 * directly, and a nullable field is a plain optional there where a sealed type
 * would need SKIE's onEnum wrapper at every use.
 */
data class MatchListItem(
    /** Stable for the row's lifetime. "video-<id>" for anything filmed, "score-<id>" for a scored match. */
    val key: String,
    val sortAtEpochMs: Long,
    val title: String,
    /** "3-1 · 12 rallies · Sep 8, 2026". */
    val detail: String,
    val playersLine: String?,
    /** "Shared by coach@club.es", shared rows only. */
    val sharedBy: String?,
    val where: MatchWhere?,
    val analyses: List<MatchAnalysis>,
    /** "Not analyzed", "Analyzing 42%…", a failure. Null when the glyphs say everything. */
    val statusText: String?,
    val statusIsError: Boolean,
    val action: MatchAction,
    /** The local entry, when the file is on this phone. Equal to [videoId] once it has been uploaded. */
    val entryId: String?,
    /** The cloud video, when it has clips. */
    val videoId: String?,
    val scoreLogId: String?,
    /** Thumbnail sources, first non-null wins: the file on this phone, the cloud's cover clip, the score. */
    val localUri: String?,
    val coverClip: RallyClip?,
    /** The last game's score for a tile, e.g. "21-18". Null for a match nobody has scored. */
    val scoreText: String?,
    val isOwned: Boolean,
    val canShare: Boolean,
    val canEditDetails: Boolean,
    val canAnalyzeAgain: Boolean,
    val canRemoveFromPhone: Boolean,
    val delete: MatchDelete,
    /** A tap opens the local player rather than the match page: the file is here and nothing else is. */
    val opensLocalPlayer: Boolean,
    /** Rallies an on-device run cut, for the "Clips on this phone (N)" menu item. */
    val deviceClipCount: Int,
    /** The id a stored heatmap is keyed by, when there is one. */
    val heatmapId: String?,
    /** The whole status line, read as one sentence by a screen reader. */
    val accessibilityStatus: String,
) {
    val isRaised: Boolean get() = action != MatchAction.NONE
}

data class MatchList(
    val mine: List<MatchListItem>,
    val shared: List<MatchListItem>,
)

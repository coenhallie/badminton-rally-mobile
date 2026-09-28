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
 * the row the match list's raised card.
 */
enum class MatchAction { NONE, ANALYZE, MARK_COURT, RETRY, RUNNING, PAUSED }

/** What "Delete" does on this row, before the phone copy is considered. */
enum class MatchDelete { NONE, VIDEO_MATCH, SCORE_MATCH, BOUND_MATCH, LEAVE_SHARE }

/**
 * One match on the list. The match page reads the same item for its
 * sections.
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
    /** Rallies an on-device run cut, for the Rallies section of a match with no cloud clips. */
    val deviceClipCount: Int,
    /** The rallies [detail] prints: the cloud's clips, else what a device run cut. What "Most rallies" sorts by. */
    val rallyCount: Int,
    /**
     * Every note on the row's cloud clips, labelled or not: the sum of the
     * "N notes" each rally shows on the match page, and what "Most notes"
     * sorts by. On a shared row these are the owner's notes, which the
     * recipient reads there too. Notes on rallies a device run cut live only
     * on this phone and are not counted: a match with no cloud clips has 0.
     */
    val noteCount: Int,
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
) {
    /**
     * The row with [key] as this list draws it now, or null once it is gone.
     *
     * What a confirmation holds while it waits, rather than the item itself: the
     * list is rebuilt whenever anything under it changes (stored results arrive
     * off the main thread after the first build, a run settles, clips sync), and
     * an item captured at swipe time describes the row as it was then. The
     * "Remove video?" dialog read such an item and left out the rallies and
     * heatmap the row itself was drawing.
     */
    fun itemWithKey(key: String): MatchListItem? =
        mine.firstOrNull { it.key == key } ?: shared.firstOrNull { it.key == key }

    /**
     * The row an open [kind] confirmation is for, as drawn now, or null when
     * the dialog must close for good: the row has gone, or it no longer offers
     * what the dialog confirms (a device run that starts under the dialog holds
     * the file, so neither removing it nor deleting the match is offered any
     * more). Both platforms clear the held key on null, rather than only hiding
     * the dialog, so a row that comes back does not reopen it on its own.
     */
    fun confirmationTarget(key: String, kind: MatchConfirmation): MatchListItem? =
        itemWithKey(key)?.takeIf { item ->
            when (kind) {
                MatchConfirmation.CHOICE ->
                    item.canRemoveFromPhone && item.delete != MatchDelete.NONE && item.delete != MatchDelete.LEAVE_SHARE
                MatchConfirmation.DELETE -> item.delete != MatchDelete.NONE
                MatchConfirmation.REMOVE -> item.canRemoveFromPhone
            }
        }
}

/**
 * The match list's three confirmations: the two-way choice (remove the video, or
 * delete the match everywhere), the single delete (or leave, for a shared
 * match), and "Remove video?".
 */
enum class MatchConfirmation { CHOICE, DELETE, REMOVE }

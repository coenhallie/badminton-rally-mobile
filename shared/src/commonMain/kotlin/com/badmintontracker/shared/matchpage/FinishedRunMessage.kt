package com.badmintontracker.shared.matchpage

import com.badmintontracker.shared.matchlist.MatchAnalysis
import com.badmintontracker.shared.matchlist.MatchList
import com.badmintontracker.shared.matchlist.MatchListItem

/**
 * What the list says when runs land: [text], and the one match its View opens,
 * or null when several landed at once (the rows already show which).
 */
data class FinishedRunMessage(val text: String, val item: MatchListItem?)

/**
 * The announcement for the run ids `announceFinishedRuns` just returned.
 *
 * Ids are entry ids for a device run and entry-or-video ids for a cloud one,
 * so a row is matched on either. Null when none of them is a row any more:
 * a match deleted while its run was finishing has nothing to announce.
 *
 * A row that still has no analysis (no rallies, no heatmap, no skeleton; a
 * score alone is not one) is left out too. A run that found nothing, a short
 * clip with no rally in it, used to say "is analyzed" with a View over a row
 * that still read "Not analyzed" and still offered Analyze: two answers to one
 * question on one screen. The row says what happened; the snackbar only
 * speaks when there is something to open.
 *
 * The list is the one the caller holds when the run lands. A row that has not
 * caught up with its run yet reads as having found nothing, and is left out
 * the same way.
 */
fun finishedRunMessage(ids: Set<String>, list: MatchList): FinishedRunMessage? {
    fun hit(id: String?) = id != null && id in ids
    val items = (list.mine + list.shared)
        .filter { hit(it.entryId) || hit(it.videoId) }
        .filter { item -> item.analyses.any { it in ANALYSED } }
        .distinctBy { it.key }
    return when (items.size) {
        0 -> null
        1 -> FinishedRunMessage(MatchPageCopy.analyzed(items.single().title), items.single())
        else -> FinishedRunMessage(MatchPageCopy.analyzedCount(items.size), null)
    }
}

/** What a run leaves that makes a row analysed. [MatchAnalysis.SCORE] is the coach's, not a run's. */
private val ANALYSED = setOf(MatchAnalysis.RALLIES, MatchAnalysis.HEATMAP, MatchAnalysis.SKELETON)

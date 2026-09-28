package com.badmintontracker.shared.matchpage

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
 * It does not judge whether a run found anything from the row: the list can
 * lag the run (a cloud run's clips and track land after its finish), so a row
 * reading as unanalysed here may be one refresh from its result, and the ids
 * are cleared once read, so skipping it would lose that run for good. A
 * device run that found nothing is dropped from the ids before they get here,
 * from the run's own outcome (AuthGate's watcher, LocalAnalysisState.Done.
 * foundSomething); a cloud run that finds no rallies fails instead of
 * finishing.
 */
fun finishedRunMessage(ids: Set<String>, list: MatchList): FinishedRunMessage? {
    fun hit(id: String?) = id != null && id in ids
    val items = (list.mine + list.shared).filter { hit(it.entryId) || hit(it.videoId) }.distinctBy { it.key }
    return when (items.size) {
        0 -> null
        1 -> FinishedRunMessage(MatchPageCopy.analyzed(items.single().title), items.single())
        else -> FinishedRunMessage(MatchPageCopy.analyzedCount(items.size), null)
    }
}

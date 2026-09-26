package com.badmintontracker.shared.matchlist

enum class MatchListFilter { ALL, ON_THIS_PHONE, NOT_ANALYZED }

/**
 * Below this many matches the chips are hidden: a list that fits on one screen
 * does not need narrowing, and the chips would only add chrome.
 */
const val MATCH_LIST_FILTER_THRESHOLD = 6

fun showsFilters(mineCount: Int): Boolean = mineCount >= MATCH_LIST_FILTER_THRESHOLD

/**
 * The filter actually applied. A chosen filter stops applying the moment its
 * chips are hidden: filtering on a control the coach can no longer see would
 * hide matches with no way to get them back.
 */
fun effectiveFilter(chosen: MatchListFilter, mineCount: Int): MatchListFilter =
    if (showsFilters(mineCount)) chosen else MatchListFilter.ALL

private val ANALYSED_BY_A_RUN = setOf(MatchAnalysis.RALLIES, MatchAnalysis.HEATMAP, MatchAnalysis.SKELETON)

fun MatchListItem.passes(filter: MatchListFilter): Boolean = when (filter) {
    MatchListFilter.ALL -> true
    MatchListFilter.ON_THIS_PHONE -> where == MatchWhere.PHONE || where == MatchWhere.BOTH
    // A video on this phone that no run has produced anything for. An ANALYZED
    // entry whose clips this phone has not seen yet is analyzed, not waiting
    // (it says "Analyzed" on its row), and a match with no video has nothing
    // to analyze.
    MatchListFilter.NOT_ANALYZED -> entryId != null &&
        analyses.none { it in ANALYSED_BY_A_RUN } &&
        statusText != MatchListCopy.ANALYZED
}

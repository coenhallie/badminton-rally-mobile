package com.badmintontracker.shared.matchlist

/** The match list's orders, in the order its menu lists them. NEWEST is the list as built. */
enum class MatchListSort { NEWEST, OLDEST, MOST_RALLIES, MOST_NOTES }

/**
 * The sort actually applied. It rides with the filter chips and goes when they
 * go, for the same reason [effectiveFilter] does: an order the coach can no
 * longer see or change would leave a short list looking shuffled.
 */
fun effectiveSort(chosen: MatchListSort, mineCount: Int): MatchListSort =
    if (showsFilters(mineCount)) chosen else MatchListSort.NEWEST

/**
 * Newest first, then by key: the list's own order, and every other sort's
 * tie-break. The key makes it total, so two matches added in the same
 * millisecond cannot swap places between two builds of the list.
 */
internal val NEWEST_FIRST: Comparator<MatchListItem> =
    compareByDescending<MatchListItem> { it.sortAtEpochMs }.thenBy { it.key }

private fun comparatorFor(sort: MatchListSort): Comparator<MatchListItem> = when (sort) {
    MatchListSort.NEWEST -> NEWEST_FIRST
    // Not NEWEST_FIRST reversed: that would also reverse the key order on a
    // tie, and a tie should read the same whichever way the dates run.
    MatchListSort.OLDEST -> compareBy<MatchListItem> { it.sortAtEpochMs }.thenBy { it.key }
    MatchListSort.MOST_RALLIES -> compareByDescending<MatchListItem> { it.rallyCount }.then(NEWEST_FIRST)
    MatchListSort.MOST_NOTES -> compareByDescending<MatchListItem> { it.noteCount }.then(NEWEST_FIRST)
}

/**
 * [items] in [sort]'s order. Applied to what the filter left, and to each
 * section on its own: the shared section is never filtered, but an order the
 * coach picked for the list would be a surprise if it stopped at the header.
 */
fun sortMatches(items: List<MatchListItem>, sort: MatchListSort): List<MatchListItem> =
    items.sortedWith(comparatorFor(sort))

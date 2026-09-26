package com.badmintontracker.shared.matchpage

import com.badmintontracker.shared.matchlist.MatchAnalysis
import com.badmintontracker.shared.matchlist.MatchList
import com.badmintontracker.shared.matchlist.MatchListItem

/**
 * The parts of one match page, in the order the toggle row draws them.
 *
 * "Match", not "Video": every rally is a video too, and Match against Rallies
 * reads as the whole recording against its parts. See
 * docs/plans/2026-09-26-match-page-hub-design.md.
 */
enum class MatchPageSection(val label: String) {
    POINTS("Points"),
    MATCH("Match"),
    RALLIES("Rallies"),
    ANALYSIS("Analysis"),
}

/**
 * Which sections [item]'s page has.
 *
 * Read from the same fields the row's status line draws its chips from, so
 * the chips on a row are the sections of its page: # is Points, the phone is
 * Match, the film is Rallies, the flame or the figure is Analysis. The two
 * tabs this replaced drew the same row and disagreed about what it had; one
 * rule over one item cannot.
 *
 * One exception, on purpose: a score log nobody has started draws no # chip,
 * but its page still has Points, because Points is where scoring starts.
 */
fun matchPageSections(item: MatchListItem): List<MatchPageSection> = buildList {
    if (item.scoreLogId != null) add(MatchPageSection.POINTS)
    if (item.entryId != null) add(MatchPageSection.MATCH)
    if (MatchAnalysis.RALLIES in item.analyses) add(MatchPageSection.RALLIES)
    if (MatchAnalysis.HEATMAP in item.analyses || MatchAnalysis.SKELETON in item.analyses) {
        add(MatchPageSection.ANALYSIS)
    }
}

/**
 * Points before rallies before analysis before the raw recording: a scored
 * match opens where it always has, a filmed one on its rallies rather than the
 * whole video, and a video just imported on the Match section, which is where
 * its Analyze button is. The draw order ([MatchPageSection.entries]) is not the
 * opening order, which is why this list exists.
 */
private val OPENING_ORDER = listOf(
    MatchPageSection.POINTS, MatchPageSection.RALLIES, MatchPageSection.ANALYSIS, MatchPageSection.MATCH,
)

/** The section a page opens on, or null for a page with none (a match that is gone). */
fun defaultSection(sections: List<MatchPageSection>): MatchPageSection? =
    OPENING_ORDER.firstOrNull { it in sections }

/**
 * What the page draws: the coach's [chosen] section while it still exists,
 * else the default. A section can go while it is on screen (the file removed,
 * a re-analysis clearing the clips); the choice is kept rather than cleared,
 * so the page returns to it if it comes back.
 */
fun shownSection(chosen: MatchPageSection?, sections: List<MatchPageSection>): MatchPageSection? =
    chosen?.takeIf { it in sections } ?: defaultSection(sections)

/**
 * Where a finished run's View takes the coach: the analysis it produced, or
 * the page's default when the run cut rallies but kept no track.
 */
fun sectionAfterRun(item: MatchListItem): MatchPageSection? {
    val sections = matchPageSections(item)
    return if (MatchPageSection.ANALYSIS in sections) MatchPageSection.ANALYSIS else defaultSection(sections)
}

/**
 * The id the Analysis section reads its track, skeleton and clips by: the
 * heatmap's own id (the entry's, or the cloud video's once the file has gone),
 * else the entry, for a match with a skeleton and no track.
 */
fun analysisId(item: MatchListItem): String? = item.heatmapId ?: item.entryId

/**
 * The row a match page is showing, as the list draws it now, or null once it
 * is gone.
 *
 * By score log first, because a scored match's log is its identity whatever
 * video it gains. The two video ids are tried both ways round: a local entry's
 * id becomes its `videos.id` on Analyze, so a page opened on the entry keeps
 * finding its row after the file has left the phone, and a page opened on a
 * cloud video finds a row that still has its file here.
 */
fun MatchList.matchPageItem(scoreLogId: String?, videoId: String?, entryId: String?): MatchListItem? {
    val all = mine + shared
    if (scoreLogId != null) all.firstOrNull { it.scoreLogId == scoreLogId }?.let { return it }
    for (id in listOfNotNull(videoId, entryId)) {
        all.firstOrNull { it.videoId == id || it.entryId == id }?.let { return it }
    }
    return null
}

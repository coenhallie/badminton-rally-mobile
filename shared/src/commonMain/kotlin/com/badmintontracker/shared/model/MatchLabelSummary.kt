package com.badmintontracker.shared.model

import kotlin.math.roundToInt

/**
 * One label's share of a match. [sharePercent] is rounded here rather than on
 * each platform so Android and iOS can never show 45% and 46% for the same row.
 */
data class LabelCount(
    val name: String,
    val colorKey: String?,
    val count: Int,
    val sharePercent: Int,
)

/** The rally carrying the most labelled notes in a match. */
data class TopRally(
    val clipId: String,
    val rallyIndex: Int,
    val labelCount: Int,
)

/**
 * How a match was tagged. Built by [buildMatchLabelSummary], which owns every
 * counting rule so the two platforms cannot drift apart on them.
 *
 * Counts labelled notes, not notes. `rally_clips.annotation_count`, which drives
 * the "N NOTES" line and the most-notes sort, counts every annotation, and the
 * schema deliberately allows one with a body and no label. The two numbers
 * differ on real data, which is why every string built from this says
 * "labelled notes".
 */
data class MatchLabelSummary(
    val labelledNoteCount: Int,
    val labels: List<LabelCount>,
    val topRally: TopRally?,
) {
    val isEmpty: Boolean get() = labelledNoteCount == 0

    companion object {
        val EMPTY = MatchLabelSummary(labelledNoteCount = 0, labels = emptyList(), topRally = null)
    }
}

/**
 * One use of a label, reduced to what counting needs.
 *
 * [recency] decides which spelling and colour a group displays - annotations use
 * their creation time, scored points use their ordinal, higher wins. [tieBreak]
 * settles an exact tie so the answer never depends on row order: annotations pass
 * their id, and points pass nothing because ordinals cannot tie.
 */
data class LabelRef(
    val name: String,
    val colorKey: String?,
    val recency: Long,
    val tieBreak: String,
)

/**
 * Rolls label uses up into counts. The single home of every counting rule the app
 * has: which names group together, which spelling a group shows, how a share is
 * rounded, and how equal counts are ordered.
 *
 * It exists as its own function because two features roll labels up - the rally
 * page's summary and the courtside tag tally - and a rule implemented twice is a
 * rule that will eventually disagree with itself in front of the same user.
 */
fun rollUpLabels(refs: List<LabelRef>): List<LabelCount> {
    if (refs.isEmpty()) return emptyList()
    val total = refs.size
    return refs
        .groupBy { it.name.lowercase() }
        .map { (key, group) ->
            val newest = group.maxWith(compareBy({ it.recency }, { it.tieBreak }))
            key to LabelCount(
                name = newest.name,
                colorKey = newest.colorKey,
                count = group.size,
                // Double division on purpose: integer division truncates, which
                // would print 16 next to a bar drawn at 17.
                sharePercent = ((group.size * 100.0) / total).roundToInt(),
            )
        }
        .sortedWith(
            compareByDescending<Pair<String, LabelCount>> { it.second.count }
                .thenBy { it.second.name.lowercase() }
        )
        .map { it.second }
}

/**
 * Rolls one match's annotations up into a [MatchLabelSummary]. [clips] must
 * already be filtered to the match; annotations naming a clip outside it are
 * ignored, so [MatchLabelSummary.labelledNoteCount] always equals the sum of
 * [MatchLabelSummary.labels] counts.
 *
 * Labels group on the trimmed, lowercased name. The name is a snapshot taken
 * when the note was made, so it is the only identity available, and folding
 * case matches the identity rule the DB already enforces on the live palette
 * (annotation_labels_owner_name_key, on lower(name)). Within a group the newest
 * annotation supplies the display name and colour.
 *
 * Every ordering is fully determined: a strip that reorders two equal-count
 * labels between refreshes reads as a glitch.
 */
fun buildMatchLabelSummary(
    clips: List<RallyClip>,
    annotations: List<RallyAnnotation>,
): MatchLabelSummary {
    val clipsById = clips.associateBy { it.id }
    val tagged = annotations.mapNotNull { annotation ->
        val label = annotation.labelName?.trim()?.takeIf { it.isNotEmpty() }
        if (label == null || annotation.clipId !in clipsById) null else annotation to label
    }
    if (tagged.isEmpty()) return MatchLabelSummary.EMPTY

    val total = tagged.size
    val labels = rollUpLabels(
        tagged.map { (annotation, label) ->
            LabelRef(
                name = label,
                colorKey = annotation.labelColor,
                // Microseconds, not milliseconds: timestamptz stores microseconds,
                // and collapsing to millis would invent ties that then fall through
                // to the id and could flip which colour a group shows.
                recency = annotation.createdAt.epochSeconds * 1_000_000 +
                    annotation.createdAt.nanosecondsOfSecond / 1_000,
                tieBreak = annotation.id,
            )
        }
    )

    val topRally = tagged
        .groupingBy { (annotation, _) -> annotation.clipId }
        .eachCount()
        .mapNotNull { (clipId, count) ->
            clipsById[clipId]?.let { TopRally(it.id, it.rallyIndex, count) }
        }
        .sortedWith(compareByDescending<TopRally> { it.labelCount }.thenBy { it.rallyIndex })
        .firstOrNull()

    return MatchLabelSummary(labelledNoteCount = total, labels = labels, topRally = topRally)
}

/**
 * The name as the summary strip shows it. Chips sit in a fixed width row beside
 * their counts and a chevron, and a label may be up to 24 characters, so long
 * names are cut here rather than allowed to push the chevron off screen. The
 * sheet always shows the full name.
 *
 * No default argument: Kotlin defaults do not survive into the generated Swift
 * initializer, so a cap passed per platform is a cap that will drift.
 */
fun stripLabelName(name: String): String =
    if (name.length <= STRIP_LABEL_MAX) name
    else name.take(STRIP_LABEL_MAX - 1).trimEnd() + "…"

private const val STRIP_LABEL_MAX = 16

package com.badmintontracker.android.match

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.cliplist.ClipRow
import com.badmintontracker.android.cliplist.MatchLabelStrip
import com.badmintontracker.shared.model.MatchLabelSummary
import com.badmintontracker.shared.model.RallyClip
import com.badmintontracker.shared.repo.MediaRepository

enum class ClipSort { RallyOrder, MostNotes }

fun sortClips(clips: List<RallyClip>, sort: ClipSort): List<RallyClip> = when (sort) {
    ClipSort.RallyOrder -> clips.sortedBy { it.rallyIndex }
    ClipSort.MostNotes -> clips.sortedWith(
        compareByDescending<RallyClip> { it.annotationCount }.thenBy { it.rallyIndex },
    )
}

/**
 * The rally half of a match: the label summary strip, the match description and
 * one row per clip. A `LazyListScope` extension rather than a screen, because the
 * match page renders it inside the same list as the points facet and one scroll
 * container per page is the whole point of merging the two screens.
 */
fun LazyListScope.ralliesFacet(
    clips: List<RallyClip>,
    summary: MatchLabelSummary?,
    matchTitle: String?,
    description: String?,
    media: MediaRepository,
    isRefreshing: Boolean,
    onSummaryClick: () -> Unit,
    onClipClick: (RallyClip) -> Unit,
) {
    // summary and clips both derive from the same clip cache (MatchSummaryViewModel
    // observes clips.observeClips() filtered to this video, same as clipsForMatch in
    // MatchScreen), so they cannot disagree about whether the match has rallies: a
    // non-empty summary never coincides with an empty clips list. That is why the
    // branches below are not mutually exclusive the way the original screen's
    // Box-vs-LazyColumn split was - the invariant holds, just incidentally rather
    // than structurally.
    if (summary != null && !summary.isEmpty) {
        item(key = "match-label-summary") {
            MatchLabelStrip(
                summary = summary,
                onClick = onSummaryClick,
            )
            HorizontalDivider()
        }
    }
    description?.let { d ->
        item(key = "match-description") {
            Text(
                text = d,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            HorizontalDivider()
        }
    }
    // Matches MatchClipsScreen's original gate: while a refresh is in flight an
    // empty clip list is "still loading", not "confirmed empty" - showing the
    // message here would flash it on every fresh view-model, including the very
    // first open of any match.
    if (clips.isEmpty() && !isRefreshing) {
        item(key = "no-rallies") {
            Box(
                Modifier.fillParentMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("No rallies in this match.")
            }
        }
    } else if (clips.isNotEmpty()) {
        items(clips, key = { it.id }) { clip ->
            ClipRow(clip, media, onClick = { onClipClick(clip) }, matchTitle = matchTitle)
            HorizontalDivider()
        }
    }
}

package com.badmintontracker.android.match

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.cliplist.ClipRow
import com.badmintontracker.android.cliplist.MatchLabelStrip
import com.badmintontracker.android.ui.components.ShuttlEmptyState
import com.badmintontracker.android.ui.icons.ShuttlIcons
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
 * The rally half of a match: the label summary strip, who played, the match
 * description and one row per clip. A `LazyListScope` extension rather than a
 * screen, because the match page's Rallies section renders it inside a list the
 * page owns, under the section's own sort row: one scroll container for the
 * section, not one nested in another.
 *
 * @param details "Coen vs Marco · Doubles" from [matchDetailsLine], or null. The
 *   Points section has its own legend for this; here it is what a match that was
 *   only ever filmed has to say about itself once its details are filled in.
 */
fun LazyListScope.ralliesFacet(
    clips: List<RallyClip>,
    summary: MatchLabelSummary?,
    matchTitle: String?,
    details: String?,
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
            HorizontalDivider(color = RallyDivider)
        }
    }
    // One block for both lines, so a match with a players line and a description
    // gets one divider under them rather than a ruled row each.
    if (details != null || description != null) {
        item(key = "match-details") {
            Column(Modifier.padding(horizontal = MatchPageGutter, vertical = MatchRowInset)) {
                details?.let { d ->
                    Text(
                        text = d,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                description?.let { d ->
                    Text(
                        text = d,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = if (details != null) Modifier.padding(top = 4.dp) else Modifier,
                    )
                }
            }
            HorizontalDivider(color = RallyDivider)
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
                ShuttlEmptyState(
                    icon = ShuttlIcons.Video,
                    title = "No rallies yet",
                    body = "Rallies show up here once this match's video has been clipped.",
                )
            }
        }
    } else if (clips.isNotEmpty()) {
        items(clips, key = { it.id }) { clip ->
            ClipRow(clip, media, onClick = { onClipClick(clip) }, matchTitle = matchTitle)
            HorizontalDivider(color = RallyDivider)
        }
    }
}

/**
 * The rule between rally rows, cloud and device alike: the border token, set
 * here rather than left to DividerDefaults so the two lists cannot drift.
 */
internal val RallyDivider: Color
    @Composable get() = MaterialTheme.colorScheme.outlineVariant


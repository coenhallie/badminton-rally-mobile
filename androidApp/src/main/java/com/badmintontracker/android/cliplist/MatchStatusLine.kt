package com.badmintontracker.android.cliplist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.matchlist.MatchAction
import com.badmintontracker.shared.matchlist.MatchAnalysis
import com.badmintontracker.shared.matchlist.MatchListItem
import com.badmintontracker.shared.matchlist.MatchWhere

/**
 * Where a match's video is, then what it has. Read as one sentence by
 * TalkBack ([MatchListItem.accessibilityStatus]); the glyphs are for scanning.
 *
 * Sized against the drawer's former ~138dp text column: two bare 14dp
 * where-glyphs, a divider, and up to four 18dp analysis chips is 127dp - the
 * floor this line was built to fit, comfortably inside the wider column the
 * full-width match list gives it now.
 */
@Composable
fun MatchStatusLine(item: MatchListItem, modifier: Modifier = Modifier) {
    val whereIcons = item.where?.icons().orEmpty()
    val running = item.action == MatchAction.RUNNING
    // A failure has its own line under the row: two lines of reason do not fit here.
    val inlineText = item.statusText?.takeUnless { item.statusIsError }
    Row(
        modifier = modifier
            // One height for every status line, chips or not: a line of bare
            // glyphs and words would otherwise sit 2dp shorter than its
            // neighbour's, and rows of a list that differ by 2dp read as a bug.
            .heightIn(min = ChipSize)
            .clearAndSetSemantics { contentDescription = item.accessibilityStatus },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        whereIcons.forEachIndexed { i, icon ->
            if (i > 0) Spacer(Modifier.width(2.dp))
            WhereGlyph(icon)
        }
        if (whereIcons.isNotEmpty() && (item.analyses.isNotEmpty() || running || inlineText != null)) {
            Box(
                Modifier
                    .padding(horizontal = 6.dp)
                    .size(1.dp, 12.dp)
                    .background(MaterialTheme.colorScheme.outline),
            )
        }
        item.analyses.forEachIndexed { i, a ->
            if (i > 0) Spacer(Modifier.width(4.dp))
            AnalysisChip(a.icon())
        }
        // The divider brings its own 6dp; after a chip the gap is spelled out.
        // Nothing before it at all (a scored match with no video) and the line
        // starts flush with the text above it.
        val afterChip = item.analyses.isNotEmpty()
        if (running) {
            if (afterChip) Spacer(Modifier.width(6.dp))
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
        }
        inlineText?.let { text ->
            if (afterChip || running) Spacer(Modifier.width(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Not fill: a short status keeps its own width, a long one
                // ellipsizes inside the column instead of pushing past it.
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/**
 * Whether [MatchStatusLine] would draw anything. A scored match nobody has
 * started, with no video, has nothing to say, and an empty line would still
 * cost its row the height and the gap above it.
 */
internal val MatchListItem.hasStatusLine: Boolean
    get() = where != null || analyses.isNotEmpty() || action == MatchAction.RUNNING ||
        (statusText != null && !statusIsError)

private val ChipSize = 18.dp

/** A where-glyph, bare: where the video is reads as context, not as an achievement. */
@Composable
internal fun WhereGlyph(icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** An analysis the match has, in its tinted chip. */
@Composable
internal fun AnalysisChip(icon: ImageVector) {
    Box(
        Modifier
            .size(ChipSize)
            .clip(RoundedCornerShape(6.dp))
            .background(ShuttlTheme.extended.analysisChip),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp), tint = ShuttlTheme.extended.analysisGlyph)
    }
}

internal fun MatchWhere.icons(): List<ImageVector> = when (this) {
    MatchWhere.PHONE -> listOf(ShuttlIcons.Smartphone)
    MatchWhere.CLOUD -> listOf(ShuttlIcons.Cloud)
    MatchWhere.BOTH -> listOf(ShuttlIcons.Smartphone, ShuttlIcons.Cloud)
}

internal fun MatchAnalysis.icon(): ImageVector = when (this) {
    MatchAnalysis.RALLIES -> ShuttlIcons.Film
    MatchAnalysis.HEATMAP -> ShuttlIcons.Flame
    MatchAnalysis.SKELETON -> ShuttlIcons.PersonStanding
    MatchAnalysis.SCORE -> ShuttlIcons.Hash
}

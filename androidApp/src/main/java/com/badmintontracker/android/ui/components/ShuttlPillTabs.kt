package com.badmintontracker.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badmintontracker.android.ui.theme.ShuttlRadius

/**
 * The mock's tab switcher: a pill-shaped track holding one pill per choice,
 * the chosen one filled with the heading colour and lettered in the page
 * colour, the rest lettered in the tertiary text colour.
 *
 * This replaces M3's SingleChoiceSegmentedButtonRow on the screens that were
 * built from the mock. That control draws a bordered, checkmarked segment,
 * which is Material's language and not this app's. Every pill takes an equal
 * share of the track, so labels of different lengths do not make the pills
 * ragged, and a long label wraps inside its pill rather than growing it.
 *
 * It wraps between words, never inside one. Where a pill is too narrow for
 * its longest word (the match page's four sections at a large font scale on a
 * 360dp phone broke "Analysis" into "Analys / is"), the pills first give up
 * some of their side padding, and only if that is not enough is every label
 * in the row set a step smaller, by the one factor the tightest word needs, so
 * the row keeps one size. Padding first because a smaller label is visible
 * against the row stacked under it (the Analysis section's panels); a few dp
 * of air inside a pill is not. See [pillPadding] and [fitScale]. The factor
 * is checked against the words measured at the size it gives, not assumed
 * from the first measure, since Android scales large text by less than small
 * text (non-linear font scaling) and a linear guess could leave a word a few
 * pixels too wide.
 *
 * [secondary] is the row that belongs to another one above it: the match
 * page's Analysis panels (Heatmap | Base | Skeleton) under its sections, and
 * the Near | Far toggle inside a panel. Shorter pills and a smaller label,
 * labelSmall's size and weight, in the same colours: three rows of one size
 * stacked in the Analysis section read as three peers, with nothing to say
 * the second was Analysis's own.
 *
 * A primary row shrinks its labels on a narrow phone at a large font scale,
 * and a secondary one under it could then come out the larger of the two.
 * [ceilingFor] is the row that sets the most the secondary rows under it may
 * draw: it records its own label size there, and every secondary row inside
 * a [LocalPillLabelCeiling] holding that ceiling draws no larger. The match
 * page's section row sets it for the whole of its sections.
 *
 * [labels] and [selectedIndex] rather than a generic type: the callers keep
 * their own enum and pass its labels, which keeps this file free of them.
 */
@Composable
fun ShuttlPillTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    secondary: Boolean = false,
    ceilingFor: PillLabelCeiling? = null,
) {
    val shape = RoundedCornerShape(ShuttlRadius.pill)
    val baseStyle = if (secondary) {
        // labelSmall's size and weight, untracked: its tracking is for the
        // uppercase labels it was made for. 11sp against the primary's 13sp,
        // and not the transport pills' 12sp: at a large font scale on a
        // narrow phone the primary row gives its labels up to 0.75x to keep
        // "Analysis" whole, and at 12sp the row under it came out the larger.
        MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp)
    } else {
        MaterialTheme.typography.labelMedium
    }
    val padV = if (secondary) SECONDARY_PILL_PADDING_V else PILL_PADDING_V
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Each pill's width: the track less its padding and the gaps, shared
        // equally. A pixel under that, for the rounding the Row does when it
        // hands out the weights.
        val pillWidthPx = with(density) {
            ((maxWidth - TRACK_PADDING * 2 - GAP * (labels.size - 1).coerceAtLeast(0)) /
                labels.size.coerceAtLeast(1)).toPx() - 1f
        }
        // Only a secondary row is held under a ceiling; reading it here is
        // what redraws the row when the primary above it changes size.
        val ceilingPx = if (secondary) LocalPillLabelCeiling.current?.px else null
        val fit = remember(labels, baseStyle, pillWidthPx, density, ceilingPx) {
            fun scaled(scale: Float) = if (scale >= 1f) baseStyle else baseStyle.copy(
                fontSize = baseStyle.fontSize * scale,
                lineHeight = baseStyle.lineHeight * scale,
                letterSpacing = baseStyle.letterSpacing * scale,
            )
            val words = labels.flatMap { it.split(' ') }.filter { it.isNotEmpty() }
            fun widestWord(scale: Float): Float = words.maxOfOrNull { word ->
                measurer.measure(word, scaled(scale), softWrap = false, maxLines = 1).size.width.toFloat()
            } ?: 0f
            val padPx = pillPadding(
                pillWidth = pillWidthPx,
                widestWord = widestWord(1f),
                min = with(density) { PILL_PADDING_H_MIN.toPx() },
                max = with(density) { PILL_PADDING_H.toPx() },
            )
            var scale = fitScale(limit = pillWidthPx - padPx * 2, measure = ::widestWord)
            if (ceilingPx != null) {
                scale = minOf(scale, fitScale(limit = ceilingPx) { with(density) { scaled(it).fontSize.toPx() } })
            }
            val style = scaled(scale)
            PillFit(with(density) { padPx.toDp() }, style, with(density) { style.fontSize.toPx() })
        }
        ceilingFor?.let { ceiling -> SideEffect { ceiling.px = fit.labelPx } }
        val pad = fit.pad
        val style = fit.style
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant, shape)
                .padding(TRACK_PADDING)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(GAP),
        ) {
            labels.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(shape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.surfaceVariant,
                            shape,
                        )
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) })
                        .padding(horizontal = pad, vertical = padV),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = style,
                        color = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private val TRACK_PADDING = 4.dp
private val GAP = 4.dp
private val PILL_PADDING_H = 8.dp
private val PILL_PADDING_V = 11.dp

/** A secondary pill's: with its smaller label, 8dp shorter than a primary one. */
private val SECONDARY_PILL_PADDING_V = 7.dp
/**
 * The least air either side of a label. Below this the label's first and last
 * letters crowd the capsule's curve: tried at 2dp, "Analysis" all but touched
 * it. Past this point the label shrinks instead.
 */
private val PILL_PADDING_H_MIN = 6.dp

/**
 * A pill's side padding: [max] while the widest word fits inside it, else
 * whatever is left either side of that word, down to [min]. All in pixels.
 */
internal fun pillPadding(pillWidth: Float, widestWord: Float, min: Float, max: Float): Float =
    ((pillWidth - widestWord) / 2f).coerceIn(min, max)

/**
 * The largest factor, at most 1, at which [measure] (a width, or a size, at
 * that factor) comes to no more than [limit]. Checked against [measure]
 * again at each guess rather than assumed linear: Android scales large text
 * by less than small text, so halving a factor does not halve the width.
 * Floored at [MIN_PILL_LABEL_SCALE]: below that a label is too small to
 * read, and a break inside the word is the lesser harm.
 */
internal fun fitScale(limit: Float, measure: (Float) -> Float): Float {
    var scale = 1f
    repeat(8) {
        val size = measure(scale)
        if (size <= limit || size <= 0f || scale <= MIN_PILL_LABEL_SCALE) return scale
        // A hair under the proportional guess, so a guess that lands exactly
        // on the limit is not rounded back over it by the text measurer.
        scale = (scale * limit / size * 0.995f).coerceIn(MIN_PILL_LABEL_SCALE, 1f)
    }
    return scale
}

/** What a row draws at: its pills' side padding, its label style, and that style's size in pixels. */
private data class PillFit(val pad: Dp, val style: TextStyle, val labelPx: Float)

/**
 * The label size, in pixels, of the primary row that secondary rows under it
 * may not exceed. See [ShuttlPillTabs]' ceilingFor. Unbounded until that row
 * has drawn, and on a page that has none.
 */
@Stable
class PillLabelCeiling {
    var px by mutableFloatStateOf(Float.POSITIVE_INFINITY)
}

/** The ceiling the secondary pill rows below read. Null where no primary row sets one. */
val LocalPillLabelCeiling = staticCompositionLocalOf<PillLabelCeiling?> { null }

internal const val MIN_PILL_LABEL_SCALE = 0.75f

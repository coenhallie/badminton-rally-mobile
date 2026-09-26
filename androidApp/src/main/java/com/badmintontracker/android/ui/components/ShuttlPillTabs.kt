package com.badmintontracker.android.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
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
 * of air inside a pill is not. See [pillPadding] and [pillLabelScale].
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
) {
    val shape = RoundedCornerShape(ShuttlRadius.pill)
    val baseStyle = MaterialTheme.typography.labelMedium
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
        val fit = remember(labels, baseStyle, pillWidthPx, density) {
            val wordWidths = labels.flatMap { it.split(' ') }.filter { it.isNotEmpty() }.map { word ->
                measurer.measure(word, baseStyle, softWrap = false, maxLines = 1).size.width.toFloat()
            }
            val padPx = pillPadding(
                pillWidth = pillWidthPx,
                widestWord = wordWidths.maxOrNull() ?: 0f,
                min = with(density) { PILL_PADDING_H_MIN.toPx() },
                max = with(density) { PILL_PADDING_H.toPx() },
            )
            val scale = pillLabelScale(wordWidths, pillWidthPx - padPx * 2)
            val style = if (scale >= 1f) baseStyle else baseStyle.copy(
                fontSize = baseStyle.fontSize * scale,
                lineHeight = baseStyle.lineHeight * scale,
                letterSpacing = baseStyle.letterSpacing * scale,
            )
            with(density) { padPx.toDp() } to style
        }
        val (pad, style) = fit
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
                        .padding(horizontal = pad, vertical = 11.dp),
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
 * The factor every label in a row is set at so that no word has to break:
 * 1 when the widest word already fits [available], else what shrinks the
 * widest word to it. Floored at [MIN_PILL_LABEL_SCALE]: below that the label
 * is too small to read, and a break inside the word is the lesser harm.
 */
internal fun pillLabelScale(wordWidths: List<Float>, available: Float): Float {
    val widest = wordWidths.maxOrNull() ?: return 1f
    if (widest <= available || widest <= 0f) return 1f
    return (available / widest).coerceIn(MIN_PILL_LABEL_SCALE, 1f)
}

internal const val MIN_PILL_LABEL_SCALE = 0.75f

package com.badmintontracker.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.android.ui.theme.ShuttlTypeExtras

/** The number with its unit appended in [unitStyle]. */
private fun statTileText(value: String, unit: String, unitStyle: TextStyle): AnnotatedString = buildAnnotatedString {
    append(value)
    withStyle(unitStyle.toSpanStyle()) { append(unit) }
}

/**
 * One number with its unit and a caption, on a card: the mock's stat tile,
 * shared by the heatmap's summary and the skeleton view's measurements.
 *
 * The unit is set smaller and quieter than the number, in the same line, so
 * "1.76 m" reads as one value with the number carrying the weight. The
 * caption sits above the number when the tile is one of a row that a coach
 * scans by name first (the measurements), and below it when the number is
 * the thing (the heatmap's summary); [labelAbove] picks.
 *
 * A selectable tile draws a one-pixel accent border when chosen and a
 * transparent one otherwise, so choosing a tile never moves its neighbours.
 */
@Composable
fun ShuttlStatTile(
    value: String,
    unit: String,
    label: String,
    modifier: Modifier = Modifier,
    labelAbove: Boolean = false,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(ShuttlRadius.large)
    val border = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val unitColor = ShuttlTheme.extended.textTertiary
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .border(1.dp, border, shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        if (labelAbove) Caption(label)
        Text(
            statTileText(value, unit, MaterialTheme.typography.bodyMedium.copy(color = unitColor)),
            style = ShuttlTypeExtras.statNumber,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            modifier = Modifier.padding(top = if (labelAbove) 4.dp else 0.dp),
        )
        if (!labelAbove) Caption(label, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Caption(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        style = MaterialTheme.typography.bodySmall,
        color = ShuttlTheme.extended.textTertiary,
        maxLines = 1,
        modifier = modifier,
    )
}

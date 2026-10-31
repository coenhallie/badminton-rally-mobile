package com.badmintontracker.android.players

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.players.initials

/** A player's row avatar. */
val PlayerAvatarRowSize = 40.dp

/** The avatar on a player's profile. */
val PlayerAvatarProfileSize = 60.dp

/**
 * A player's initials on a disc in the home side's colour: [PlayerAvatarRowSize]
 * on a row, [PlayerAvatarProfileSize] on the profile. Decorative to a screen
 * reader, since the name is always beside it.
 *
 * The letters scale with the disc rather than with the font scale (the mock's
 * 15px on 40, 21px on 60): two letters at a large font scale would overrun a
 * disc whose size is fixed. Mirrors iosApp's PlayerAvatar.swift.
 */
@Composable
fun PlayerAvatar(name: String, size: Dp, modifier: Modifier = Modifier) {
    val letterSize = with(LocalDensity.current) { (size * LETTERS_PER_DISC).toSp() }
    Box(
        modifier = modifier
            .size(size)
            .background(ShuttlTheme.extended.sideHome, CircleShape)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials(name),
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = letterSize,
                lineHeight = letterSize,
                fontWeight = FontWeight.Bold,
            ),
            color = ShuttlTheme.extended.playerInitials,
            maxLines = 1,
        )
    }
}

/** The mock's letter size against its disc: 15 on 40, 21 on 60. */
private const val LETTERS_PER_DISC = 0.36f

package com.badmintontracker.android.players

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.badmintontracker.analysis.player.CourtSide
import com.badmintontracker.android.scoring.SectionLabel
import com.badmintontracker.android.ui.components.ShuttlOutlinedTextField
import com.badmintontracker.android.ui.components.ShuttlPillTabs
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.shared.players.EndsDraft
import com.badmintontracker.shared.players.Player
import com.badmintontracker.shared.players.PlayersCopy
import com.badmintontracker.shared.players.Tracked
import com.badmintontracker.shared.players.showsDeviceLine
import com.badmintontracker.shared.players.swapped
import com.badmintontracker.shared.players.withName
import com.badmintontracker.shared.players.withPlayer
import com.badmintontracker.shared.players.withTracked
import com.badmintontracker.shared.scoring.MAX_PLAYER_NAME
import com.badmintontracker.shared.scoring.Side

/**
 * "Who's playing", for a match born as a video: One player | Both players,
 * then the court as the camera sees it, Far above Near (section 4.3 of the
 * player profiles design).
 *
 * In One mode the player's end is a picker and the other end a typed opponent,
 * never a second picker: a profile chosen there would drop the name typed for
 * it, so the player is moved to the other end with Swap ends instead. In Both
 * mode both ends are pickers, each offering every player but the one at the
 * other end. When the player is at the far end, a line says the phone's own
 * analysis follows only the near one.
 *
 * Only renders [draft] and hands each tap back as the next draft, through the
 * shared EndsDraft functions, which hold every rule about which end keeps whom.
 * Singles only, as EndsDraft is: a caller never shows this for a doubles match.
 *
 * [uploading] shows the section as it stands, read-only, with the sentence
 * saying it can be changed once the upload finishes.
 */
@Composable
fun WhoIsPlayingSection(
    draft: EndsDraft,
    players: List<Player>,
    onChange: (EndsDraft) -> Unit,
    onAddPlayer: AddPlayer,
    modifier: Modifier = Modifier,
    uploading: Boolean = false,
) {
    val editable = !uploading
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel(PlayersCopy.WHOS_PLAYING_TITLE)
        ShuttlPillTabs(
            labels = listOf(PlayersCopy.ONE_PLAYER, PlayersCopy.BOTH_PLAYERS),
            selectedIndex = if (draft.tracked == Tracked.ONE) 0 else 1,
            onSelect = { onChange(draft.withTracked(if (it == 0) Tracked.ONE else Tracked.BOTH)) },
            groupLabel = PlayersCopy.WHOS_PLAYING_TITLE,
            enabled = editable,
        )
        Court(
            far = { mod -> End(CourtSide.FAR, draft, players, onChange, onAddPlayer, editable, mod) },
            near = { mod -> End(CourtSide.NEAR, draft, players, onChange, onAddPlayer, editable, mod) },
        )
        if (draft.tracked == Tracked.ONE) {
            TextButton(
                onClick = { onChange(draft.swapped()) },
                enabled = editable,
                modifier = Modifier.align(Alignment.End),
            ) {
                Icon(ShuttlIcons.Swap, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(PlayersCopy.SWAP_ENDS)
            }
        }
        if (draft.showsDeviceLine()) Note(PlayersCopy.DEVICE_ANALYSIS_LINE)
        if (uploading) Note(PlayersCopy.UPLOAD_IN_PROGRESS)
    }
}

/**
 * One end of the court: a picker for a player's end, else (One mode, the
 * other end) the opponent's name. Which end holds the player is the draft's
 * to say ([EndsDraft.playerAtNear]).
 */
@Composable
private fun End(
    end: CourtSide,
    draft: EndsDraft,
    players: List<Player>,
    onChange: (EndsDraft) -> Unit,
    onAddPlayer: AddPlayer,
    editable: Boolean,
    modifier: Modifier,
) {
    val near = end == CourtSide.NEAR
    val playerEnd = draft.tracked == Tracked.BOTH || draft.playerAtNear == near
    if (playerEnd) {
        val id = if (near) draft.nearPlayerId else draft.farPlayerId
        val otherId = if (near) draft.farPlayerId else draft.nearPlayerId
        PlayerPicker(
            selectedId = id,
            // One player cannot stand at both ends; in One mode the other end
            // holds no profile, so this hides nobody there.
            players = players.filter { it.id != otherId },
            onPick = { onChange(draft.withPlayer(end, it.id)) },
            onClear = { onChange(draft.withPlayer(end, null)) },
            onAddPlayer = onAddPlayer,
            enabled = editable,
            modifier = modifier,
        )
    } else {
        ShuttlOutlinedTextField(
            value = if (near) draft.nearName else draft.farName,
            onValueChange = { onChange(draft.withName(end, it)) },
            label = PlayersCopy.OPPONENT_FIELD,
            maxLength = MAX_PLAYER_NAME,
            enabled = editable,
            modifier = modifier,
        )
    }
}

/**
 * The court card: Far over Near with a dashed net between, each end's label
 * at its left. The labels sit on the control's text baseline (the picker's
 * name, the typed opponent's text) rather than on the half's centre, since a
 * typed name carries its own label above the box and would pull a centred
 * label up off it. Both labels take the wider one's width, so the two
 * controls start at one x at any font scale.
 */
@Composable
private fun Court(
    far: @Composable (Modifier) -> Unit,
    near: @Composable (Modifier) -> Unit,
) {
    val shape = RoundedCornerShape(ShuttlRadius.small)
    val netColor = MaterialTheme.colorScheme.outline
    val tagStyle = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val tagWidth = remember(tagStyle, density) {
        val widest = listOf(PlayersCopy.FAR, PlayersCopy.NEAR).maxOf {
            measurer.measure(it, tagStyle, softWrap = false, maxLines = 1).size.width
        }
        with(density) { widest.toDp() }
    }

    @Composable
    fun Half(tag: String, control: @Composable (Modifier) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = tag,
                style = tagStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(tagWidth).alignBy(LastBaseline),
            )
            Spacer(Modifier.width(12.dp))
            control(Modifier.weight(1f).alignBy(LastBaseline))
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, shape),
    ) {
        Half(PlayersCopy.FAR, far)
        // The net: dashed, as the mock draws it, across the card's inner width.
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(NetStroke)
                .drawBehind {
                    drawLine(
                        color = netColor,
                        start = Offset(0f, size.height / 2),
                        end = Offset(size.width, size.height / 2),
                        strokeWidth = size.height,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                    )
                },
        )
        Half(PlayersCopy.NEAR, near)
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val NetStroke = 2.dp

/**
 * Saves an [EndsDraft] across recreation, for the editors that hold one in
 * rememberSaveable; null (no section) saves as an empty list.
 */
internal val EndsDraftSaver = listSaver<EndsDraft?, Any?>(
    save = { d ->
        if (d == null) emptyList()
        else listOf(d.tracked.name, d.nearPlayerId, d.farPlayerId, d.nearName, d.farName, d.playerAtNear, d.nearSide?.name)
    },
    restore = {
        if (it.isEmpty()) {
            null
        } else {
            EndsDraft(
                tracked = Tracked.valueOf(it[0] as String),
                nearPlayerId = it[1] as String?,
                farPlayerId = it[2] as String?,
                nearName = it[3] as String,
                farName = it[4] as String,
                playerAtNear = it[5] as Boolean,
                nearSide = (it[6] as String?)?.let(Side::valueOf),
            )
        }
    },
)

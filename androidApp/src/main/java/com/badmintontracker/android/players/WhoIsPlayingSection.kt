package com.badmintontracker.android.players

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.badmintontracker.analysis.player.CourtSide
import com.badmintontracker.android.scoring.SectionLabel
import com.badmintontracker.android.ui.components.ShuttlOutlinedTextField
import com.badmintontracker.android.ui.components.ShuttlPillTabs
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.players.EndField
import com.badmintontracker.shared.players.EndsDraft
import com.badmintontracker.shared.players.Player
import com.badmintontracker.shared.players.PlayersCopy
import com.badmintontracker.shared.players.Tracked
import com.badmintontracker.shared.players.field
import com.badmintontracker.shared.players.showsDeviceLine
import com.badmintontracker.shared.players.swapped
import com.badmintontracker.shared.players.withName
import com.badmintontracker.shared.players.withPlayer
import com.badmintontracker.shared.players.withSomeoneElse
import com.badmintontracker.shared.players.withTracked
import com.badmintontracker.shared.scoring.MAX_PLAYER_NAME

/**
 * "Who's playing", for a match born as a video: One player | Both players,
 * then the court as the camera sees it, Far above Near (section 4.3 of the
 * player profiles design).
 *
 * In One mode the player's end is a picker and the other end a typed opponent,
 * never a second picker: a profile chosen there would drop the name typed for
 * it, so the player is moved to the other end with Swap ends instead. In Both
 * mode both ends are pickers, each offering every player but the one at the
 * other end. Every picker also offers "Someone else", which shows the end's
 * typed name under it, so no typed name is ever out of sight. When the player is at the far end, a line says the phone's own
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
            farLine = textLine(draft.field(CourtSide.FAR)),
            nearLine = textLine(draft.field(CourtSide.NEAR)),
            far = { mod -> End(CourtSide.FAR, draft, players, onChange, onAddPlayer, editable, mod) },
            near = { mod -> End(CourtSide.NEAR, draft, players, onChange, onAddPlayer, editable, mod) },
        )
        if (draft.tracked == Tracked.ONE) {
            // Out by the button's own end padding, so its label, not its
            // ripple, ends on the court card's right edge. The ripple then
            // reaches 12dp into the screen's 24dp margin, inside the scroll
            // area of both hosts (the details sheet and editor).
            val textInset = ButtonDefaults.TextButtonContentPadding.calculateEndPadding(LocalLayoutDirection.current)
            TextButton(
                onClick = { onChange(draft.swapped()) },
                enabled = editable,
                modifier = Modifier.align(Alignment.End).offset(x = textInset),
            ) {
                Icon(ShuttlIcons.Swap, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(PlayersCopy.SWAP_ENDS)
            }
        }
        if (draft.showsDeviceLine()) PlayersNote(PlayersCopy.DEVICE_ANALYSIS_LINE)
        if (uploading) PlayersNote(PlayersCopy.UPLOAD_IN_PROGRESS)
    }
}

/**
 * One end of the court, as the draft says it draws ([EndsDraft.field]): One
 * mode's other end is the typed opponent; every other end is a picker of the
 * players, "Someone else" and "Add player", with the end's typed name under it
 * once "Someone else" is its value, so a typed name at any end is on screen and
 * editable (Ruling 17).
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
    val name = if (near) draft.nearName else draft.farName
    when (val field = draft.field(end)) {
        EndField.OPPONENT -> ShuttlOutlinedTextField(
            value = name,
            onValueChange = { onChange(draft.withName(end, it)) },
            label = PlayersCopy.OPPONENT_FIELD,
            maxLength = MAX_PLAYER_NAME,
            enabled = editable,
            modifier = modifier,
        )
        EndField.PLAYER, EndField.SOMEONE_ELSE -> Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val otherId = if (near) draft.farPlayerId else draft.nearPlayerId
            PlayerPicker(
                selectedId = if (near) draft.nearPlayerId else draft.farPlayerId,
                // One player cannot stand at both ends; in One mode the other end
                // holds no profile, so this hides nobody there.
                players = players.filter { it.id != otherId },
                onPick = { onChange(draft.withPlayer(end, it.id)) },
                onClear = { onChange(draft.withPlayer(end, null)) },
                onAddPlayer = onAddPlayer,
                enabled = editable,
                someoneElse = field == EndField.SOMEONE_ELSE,
                onSomeoneElse = { onChange(draft.withSomeoneElse(end)) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (field == EndField.SOMEONE_ELSE) {
                ShuttlOutlinedTextField(
                    value = name,
                    onValueChange = { onChange(draft.withName(end, it)) },
                    label = PlayersCopy.NAME_FIELD,
                    maxLength = MAX_PLAYER_NAME,
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * The court card: Far over Near with a dashed net between, each end's label
 * at its left. The labels sit on the control's own text line rather than on
 * the half's centre: a picker's name (its first line, with any name field
 * under it), or a typed opponent's text (its last, under the field's label),
 * as [farLine] and [nearLine] say. Both labels take the wider one's width, so
 * the two controls start at one x at any font scale.
 */
@Composable
private fun Court(
    farLine: HorizontalAlignmentLine,
    nearLine: HorizontalAlignmentLine,
    far: @Composable (Modifier) -> Unit,
    near: @Composable (Modifier) -> Unit,
) {
    val shape = RoundedCornerShape(ShuttlRadius.small)
    // textTertiary, the text field's own stroke, rather than outline
    // (borderSecondary): on the dark dialog fill outline is about 1.1:1, and
    // the card and its net all but vanished. iOS draws the same token.
    val strokeColor = ShuttlTheme.extended.textTertiary
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
    fun Half(tag: String, line: HorizontalAlignmentLine, control: @Composable (Modifier) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = tag,
                style = tagStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(tagWidth).alignBy(line),
            )
            Spacer(Modifier.width(12.dp))
            control(Modifier.weight(1f).alignBy(line))
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, strokeColor, shape),
    ) {
        Half(PlayersCopy.FAR, farLine, far)
        // The net: dashed, as the mock draws it, across the card's inner width.
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(NetStroke)
                .drawBehind {
                    drawLine(
                        color = strokeColor,
                        start = Offset(0f, size.height / 2),
                        end = Offset(size.width, size.height / 2),
                        strokeWidth = size.height,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                    )
                },
        )
        Half(PlayersCopy.NEAR, nearLine, near)
    }
}

/** The line an end's label sits on: see [Court]. */
private fun textLine(field: EndField): HorizontalAlignmentLine =
    if (field == EndField.OPPONENT) LastBaseline else FirstBaseline

/** A who's-playing editor's quiet line: the device line, the doubles note, the upload sentence. */
@Composable
internal fun PlayersNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val NetStroke = 2.dp

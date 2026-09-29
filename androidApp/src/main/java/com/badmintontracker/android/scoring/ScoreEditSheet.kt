package com.badmintontracker.android.scoring

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.android.ui.theme.ShuttlTypeExtras
import com.badmintontracker.shared.scoring.MAX_EDIT_SCORE
import com.badmintontracker.shared.scoring.MatchState
import com.badmintontracker.shared.scoring.SCORE_EDIT_BLANK
import com.badmintontracker.shared.scoring.ScoreLog
import com.badmintontracker.shared.scoring.Side
import com.badmintontracker.shared.scoring.planScoreEdit
import com.badmintontracker.shared.scoring.scoreEditTitle
import com.badmintontracker.shared.scoring.sideName

/**
 * Sets the game being played to any score in one go: the board's answer to a run
 * of rallies that went unscored, or a few that went to the wrong side.
 *
 * Laid out like the board it opens over - the same two colours, each side on the
 * end it is playing from - so there is no working out which number is whose. Each
 * number is a field for typing a jump and has a stepper for the usual one-point
 * fix, which needs no keyboard. The line under them says what saving will do
 * before it is done, since lowering a score takes rallies back with their tags.
 *
 * Every rule, and every word of that line, comes from [planScoreEdit], which the
 * iOS sheet calls too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScoreEditSheet(
    log: ScoreLog,
    match: MatchState,
    onSave: (home: Int, away: Int, nextServer: Side?) -> Unit,
    onDismiss: () -> Unit,
) {
    var homeText by rememberSaveable { mutableStateOf("${match.currentGame.home}") }
    var awayText by rememberSaveable { mutableStateOf("${match.currentGame.away}") }
    // Null until the coach picks: the plan then keeps the serve where it is if it can.
    var requestedServer by rememberSaveable { mutableStateOf<Side?>(null) }

    val home = homeText.toIntOrNull()
    val away = awayText.toIntOrNull()
    val plan = remember(log, home, away, requestedServer) {
        if (home == null || away == null) null else log.planScoreEdit(home, away, requestedServer)
    }
    val left = if (match.endsSwapCount % 2 == 0) Side.HOME else Side.AWAY

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // The app's sheet colour, stated at the sheet: see AddMatchSheet.
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(match.scoreEditTitle(), style = MaterialTheme.typography.headlineMedium)

            Row(
                // Equal heights, so a two-line pair of names on one side does not
                // leave the other side's number floating higher.
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (side in listOf(left, left.other)) {
                    val text = if (side == Side.HOME) homeText else awayText
                    val setText = { value: String -> if (side == Side.HOME) homeText = value else awayText = value }
                    val value = text.toIntOrNull()
                    ScoreTile(
                        side = side,
                        name = log.sideName(side),
                        text = text,
                        onText = setText,
                        serve = when {
                            plan?.server == side -> ServeMark.Serving
                            plan?.servers?.of(side) == true -> ServeMark.Available
                            else -> ServeMark.None
                        },
                        onServe = { requestedServer = side },
                        onMinus = value?.takeIf { it > 0 }?.let { { setText("${it - 1}") } },
                        onPlus = { setText("${((value ?: -1) + 1).coerceAtMost(MAX_EDIT_SCORE)}") }
                            .takeIf { (value ?: 0) < MAX_EDIT_SCORE },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }

            val problem = plan?.problem ?: SCORE_EDIT_BLANK.takeIf { plan == null }
            Text(
                text = problem ?: plan?.summary.orEmpty(),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                // Two lines held whatever it says, so the buttons below stay put
                // while the numbers change under the coach's thumb.
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShuttlButton(
                    text = "Cancel",
                    onClick = onDismiss,
                    variant = ShuttlButtonVariant.Secondary,
                    modifier = Modifier.weight(1f),
                )
                ShuttlButton(
                    text = "Save",
                    onClick = { if (plan != null) onSave(plan.home, plan.away, plan.server) },
                    enabled = plan?.canSave == true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Where a side stands on the serve after the edit, as its tile shows it. */
private enum class ServeMark { Serving, Available, None }

@Composable
private fun ScoreTile(
    side: Side,
    name: String,
    text: String,
    onText: (String) -> Unit,
    serve: ServeMark,
    onServe: () -> Unit,
    /** Null when the stepper is at its end. */
    onMinus: (() -> Unit)?,
    onPlus: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colour = if (side == Side.HOME) ShuttlTheme.extended.sideHome else ShuttlTheme.extended.sideAway
    val focus = LocalFocusManager.current
    // The selection is held here rather than rebuilt from [text] each time, so a
    // tap into the field can select the whole number for typing straight over.
    var field by remember { mutableStateOf(TextFieldValue(text)) }
    if (field.text != text) field = TextFieldValue(text, selection = TextRange(text.length))

    Surface(modifier = modifier, shape = RoundedCornerShape(ShuttlRadius.medium), color = colour) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = name,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            // Takes up whatever the names leave, so both numbers sit level.
            Spacer(Modifier.weight(1f))

            ServeToggle(serve, onServe)

            BasicTextField(
                value = field,
                onValueChange = { next ->
                    val digits = next.text.filter { it.isDigit() }.take(2)
                    field = next.copy(text = digits)
                    onText(digits)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { state ->
                        if (state.isFocused) field = field.copy(selection = TextRange(0, field.text.length))
                    }
                    .semantics { contentDescription = "$name score" },
                // The type scale's display role, bold like the board's numerals.
                // iOS draws the same role, so the two sheets match.
                textStyle = ShuttlTypeExtras.display.copy(
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                cursorBrush = SolidColor(Color.White),
                decorationBox = { inner ->
                    // The field is drawn as a field: a well in the tile, so it
                    // reads as something to tap and type into, not just a number.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(ShuttlRadius.small))
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) { inner() }
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StepButton(ShuttlIcons.Minus, "Take a point from $name", onMinus)
                StepButton(ShuttlIcons.Plus, "Add a point for $name", onPlus)
            }
        }
    }
}

/**
 * The board's white SERVE pill, saying who serves once the edit is saved. A side
 * that could be handed the serve instead shows an outlined pill to tap; one that
 * cannot shows nothing, held at zero alpha so both numbers stay level.
 */
@Composable
private fun ServeToggle(mark: ServeMark, onServe: () -> Unit) {
    val serving = mark == ServeMark.Serving
    Surface(
        modifier = Modifier
            .alpha(if (mark == ServeMark.None) 0f else 1f)
            .then(
                if (mark == ServeMark.Available) {
                    Modifier.clickable(onClickLabel = "Serve next", role = Role.Button, onClick = onServe)
                } else Modifier,
            ),
        shape = RoundedCornerShape(ShuttlRadius.extraSmall),
        color = if (serving) Color.White else Color.Transparent,
        border = if (serving) null else BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)),
    ) {
        Text(
            text = "SERVES NEXT",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (serving) Color.Black else Color.White.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun StepButton(icon: ImageVector, label: String, onClick: (() -> Unit)?) {
    Surface(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.16f),
        modifier = Modifier.size(48.dp).alpha(if (onClick != null) 1f else 0.4f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

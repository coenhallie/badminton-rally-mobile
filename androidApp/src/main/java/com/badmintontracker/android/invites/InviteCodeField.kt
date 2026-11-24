package com.badmintontracker.android.invites

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.components.FieldLabel
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.android.ui.theme.ShuttlTypeExtras
import com.badmintontracker.shared.invites.INVITE_CODE_LENGTH
import com.badmintontracker.shared.invites.InviteCodeCell
import com.badmintontracker.shared.invites.PlayerInviteCopy
import com.badmintontracker.shared.invites.inviteCodeEntry

/**
 * The invite code entry: eight big boxes in two groups of four, drawn as the
 * decoration of one text field. One field rather than eight keeps paste
 * (the keyboard's clipboard chip), backspace and TalkBack working the way
 * they do on any field; the boxes themselves are hidden from TalkBack.
 *
 * [value] may arrive in any form ("K7M4-QP9R", "k7m4qp9r"); [onValueChange]
 * gets the bare uppercase characters, at most eight. [onSubmit] runs on the
 * keyboard's Go key, and by itself whenever a keystroke or paste completes a
 * valid code (see [inviteCodeEntry]), with the keyboard put away first.
 * The cursor is pinned to the end, so a tap on any box carries on typing
 * there instead of inserting mid-code.
 *
 * Mirrors iosApp's InviteCodeField.
 */
@Composable
fun InviteCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    autoFocus: Boolean = false,
) {
    val entry = remember(value) { inviteCodeEntry(value) }
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val label = PlayerInviteCopy.JOIN_CODE_LABEL

    // The field's own value keeps the keyboard's composing region between
    // keystrokes; an outside change (a QR scan, the verified code coming
    // back) replaces it, with the cursor at the end.
    var field by remember { mutableStateOf(TextFieldValue(entry.chars, TextRange(entry.chars.length))) }
    val shown = if (field.text == entry.chars) field else TextFieldValue(entry.chars, TextRange(entry.chars.length))

    LaunchedEffect(autoFocus) {
        if (autoFocus) focusRequester.requestFocus()
    }

    fun submit() {
        focusManager.clearFocus()
        onSubmit()
    }

    Column(modifier = modifier) {
        BasicTextField(
            value = shown,
            onValueChange = { next ->
                val nextEntry = inviteCodeEntry(next.text)
                field = TextFieldValue(
                    text = nextEntry.chars,
                    selection = TextRange(nextEntry.chars.length),
                    composition = next.composition.takeIf { nextEntry.chars == next.text },
                )
                if (nextEntry.chars != entry.chars) {
                    onValueChange(nextEntry.chars)
                    if (nextEntry.completeCode != null) submit()
                }
            },
            enabled = enabled,
            singleLine = true,
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .alpha(if (enabled) 1f else 0.6f),
            // The label sits inside the decoration, as in ShuttlOutlinedTextField,
            // so both kinds of field present their label the same way.
            decorationBox = { _ ->
                Column {
                    FieldLabel(label)
                    Spacer(Modifier.height(6.dp))
                    CodeBoxes(cells = entry.cells, typed = entry.chars.length, focused = isFocused && enabled)
                }
            },
        )
        if (entry.hasDisallowedChar) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = PlayerInviteCopy.JOIN_CODE_CHAR_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CodeBoxes(cells: List<InviteCodeCell>, typed: Int, focused: Boolean) {
    // The box the next character goes into; the last box while all are full.
    val activeIndex = if (focused) typed.coerceAtMost(INVITE_CODE_LENGTH - 1) else -1
    Row(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        cells.forEachIndexed { i, cell ->
            if (i == INVITE_CODE_LENGTH / 2) GroupDash()
            CodeBox(
                cell = cell,
                active = i == activeIndex,
                showCaret = i == activeIndex && typed < INVITE_CODE_LENGTH,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CodeBox(cell: InviteCodeCell, active: Boolean, showCaret: Boolean, modifier: Modifier) {
    // The same strokes as ShuttlOutlinedTextField: 1dp textTertiary at rest
    // (3:1 against the page), 2dp accent where typing goes. A character no
    // code contains turns its box and itself to the error colour; the
    // character is large text, so the error red's 3.8:1 on white reads.
    val shape = RoundedCornerShape(ShuttlRadius.small)
    val error = MaterialTheme.colorScheme.error
    val borderColor = when {
        !cell.isAllowed -> error
        active -> MaterialTheme.colorScheme.primary
        else -> ShuttlTheme.extended.textTertiary
    }
    val borderWidth = if (active || !cell.isAllowed) 2.dp else 1.dp
    Box(
        modifier = modifier
            .heightIn(min = 60.dp)
            .background(ShuttlTheme.extended.bgInput, shape)
            .border(borderWidth, borderColor, shape)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (showCaret) {
            Caret()
        } else {
            Text(
                text = cell.text,
                style = ShuttlTypeExtras.statNumber,
                color = if (cell.isAllowed) MaterialTheme.colorScheme.onSurface else error,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun Caret() {
    val blink = rememberInfiniteTransition(label = "caret")
    val alpha by blink.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 1000
                1f at 0
                1f at 499
                0f at 500
                0f at 999
            },
        ),
        label = "caretAlpha",
    )
    Box(
        modifier = Modifier
            .width(2.dp)
            .height(28.dp)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp)),
    )
}

@Composable
private fun GroupDash() {
    Box(
        modifier = Modifier
            .size(width = 10.dp, height = 2.dp)
            .background(ShuttlTheme.extended.textTertiary, RoundedCornerShape(1.dp)),
    )
}

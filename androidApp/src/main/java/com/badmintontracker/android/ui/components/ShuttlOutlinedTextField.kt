package com.badmintontracker.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.theme.ShuttlTheme

enum class ShuttlFieldType { Text, Email, Password }

@Composable
fun ShuttlOutlinedTextField(
    value:         String,
    onValueChange: (String) -> Unit,
    label:         String,
    modifier:      Modifier = Modifier,
    type:          ShuttlFieldType = ShuttlFieldType.Text,
    enabled:       Boolean = true,
    /** Non-null switches the IME action to Done and commits through this callback. */
    onDone:        (() -> Unit)? = null,
    /** Multi-line fields grow to [maxLines]; single-line ones scroll horizontally. */
    singleLine:    Boolean = true,
    minLines:      Int = 1,
    maxLines:      Int = if (singleLine) 1 else 4,
    /**
     * Non-null caps typing at this many characters and shows an n/max counter
     * beside the label. Enforced here rather than at the call site so no field
     * can drift out of step with the counter it displays.
     */
    maxLength:     Int? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused   by interaction.collectIsFocusedAsState()
    val borderColor =
        if (isFocused) MaterialTheme.colorScheme.primary
        else            MaterialTheme.colorScheme.outline

    val keyboard = when (type) {
        ShuttlFieldType.Email    -> KeyboardOptions(keyboardType = KeyboardType.Email)
        ShuttlFieldType.Password -> KeyboardOptions(keyboardType = KeyboardType.Password)
        ShuttlFieldType.Text     -> KeyboardOptions.Default
    }.let { if (onDone != null) it.copy(imeAction = ImeAction.Done) else it }
    val keyboardActions =
        if (onDone != null) KeyboardActions(onDone = { onDone() }) else KeyboardActions.Default
    val visual: VisualTransformation =
        if (type == ShuttlFieldType.Password) PasswordVisualTransformation()
        else                                   VisualTransformation.None

    Column(modifier = modifier.alpha(if (enabled) 1f else 0.6f)) {
        if (maxLength == null) {
            FieldLabel(label)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel(label, modifier = Modifier.weight(1f))
                Text(
                    text  = "${value.length}/$maxLength",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(ShuttlTheme.extended.bgInput)
                .border(width = 1.dp, color = borderColor)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value                  = value,
                onValueChange          = { next ->
                    onValueChange(if (maxLength != null) next.take(maxLength) else next)
                },
                enabled                = enabled,
                singleLine             = singleLine,
                minLines               = minLines,
                maxLines               = maxLines,
                interactionSource      = interaction,
                keyboardOptions        = keyboard,
                keyboardActions        = keyboardActions,
                visualTransformation   = visual,
                textStyle              = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush            = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier               = Modifier.fillMaxWidth(),
            )
        }
    }
}

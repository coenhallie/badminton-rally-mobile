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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme

enum class ShuttlFieldType { Text, Email, Password }

/**
 * The app's text field: an uppercase label above a rounded input box.
 * iosApp's ShuttlTextField draws the same thing.
 *
 * The label and the counter are drawn inside [BasicTextField]'s decoration box
 * rather than beside it. That puts them in the field's own semantics node:
 * the label is the edit box's text in the merged tree, not a separate text
 * node beside an unnamed one, and a tap on the label focuses the field.
 */
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
    /**
     * Shows Next on the keyboard, which moves focus to the following field
     * (Compose's default for that action). For a field that is not the last
     * of a form; ignored when [onDone] is set, which is the last field's.
     */
    imeNext:       Boolean = false,
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
    // The unfocused stroke is textTertiary, not the theme's outline
    // (borderSecondary): an input's boundary needs 3:1 against what it sits
    // on (WCAG 1.4.11), and borderSecondary was 1.52:1 on the light page and
    // 1.29:1 on the dark one, with the fill itself invisible in dark.
    // textTertiary is 5.82 / 5.41 / 5.41:1 on bg / bgSecondary / bgRaised in
    // light, 5.25 / 4.96 / 4.34:1 in dark. Focus stays the stronger state:
    // accent, and twice as thick. The border draws inside the box, so the
    // thicker stroke moves neither the text nor the field's size.
    val borderColor =
        if (isFocused) MaterialTheme.colorScheme.primary
        else            ShuttlTheme.extended.textTertiary
    val borderWidth = if (isFocused) 2.dp else 1.dp
    // Small, not the cards' large: a field is a control inside a surface, and
    // 12 is what the iOS system form fields already draw.
    val shape = RoundedCornerShape(ShuttlRadius.small)

    val keyboard = when (type) {
        ShuttlFieldType.Email    -> KeyboardOptions(keyboardType = KeyboardType.Email)
        ShuttlFieldType.Password -> KeyboardOptions(keyboardType = KeyboardType.Password)
        ShuttlFieldType.Text     -> KeyboardOptions.Default
    }.let {
        when {
            onDone != null -> it.copy(imeAction = ImeAction.Done)
            imeNext        -> it.copy(imeAction = ImeAction.Next)
            else           -> it
        }
    }
    val keyboardActions =
        if (onDone != null) KeyboardActions(onDone = { onDone() }) else KeyboardActions.Default
    val visual: VisualTransformation =
        if (type == ShuttlFieldType.Password) PasswordVisualTransformation()
        else                                   VisualTransformation.None

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
        cursorBrush            = SolidColor(MaterialTheme.colorScheme.primary),
        modifier               = modifier.alpha(if (enabled) 1f else 0.6f),
        decorationBox          = { innerTextField ->
            Column {
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
                        .clip(shape)
                        .background(ShuttlTheme.extended.bgInput, shape)
                        .border(width = borderWidth, color = borderColor, shape = shape)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    innerTextField()
                }
            }
        },
    )
}

package com.badmintontracker.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
    }
    val visual: VisualTransformation =
        if (type == ShuttlFieldType.Password) PasswordVisualTransformation()
        else                                   VisualTransformation.None

    Column(modifier = modifier.alpha(if (enabled) 1f else 0.6f)) {
        FieldLabel(label)
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
                onValueChange          = onValueChange,
                enabled                = enabled,
                singleLine             = true,
                interactionSource      = interaction,
                keyboardOptions        = keyboard,
                visualTransformation   = visual,
                textStyle              = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush            = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier               = Modifier.fillMaxWidth(),
            )
        }
    }
}

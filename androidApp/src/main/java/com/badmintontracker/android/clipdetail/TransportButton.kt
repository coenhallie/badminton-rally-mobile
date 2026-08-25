package com.badmintontracker.android.clipdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.theme.ShuttlTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One cell of the player's transport rows: a flat, full-bleed button that
 * distinguishes a tap from a press-and-hold. Shared by [FrameStepBar] and
 * [PlaybackControlBar] so the two rows stack as one control surface.
 *
 * @param onTap fires immediately on press, so repeated taps stay responsive.
 * @param onHoldActivate fires once, [holdActivationDelayMs] after the press.
 * @param onHoldTick repeats every [holdTickPeriodMs] after activation. Null
 *   means a hold is a one-shot gesture and no repeat loop is started.
 */
@Composable
internal fun TransportButton(
    text: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    onTap: () -> Unit = {},
    holdActivationDelayMs: Long = 400L,
    onHoldActivate: () -> Unit = {},
    onHoldTick: (() -> Unit)? = null,
    holdTickPeriodMs: Long = 100L,
    onRelease: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    // The gesture detector is started once and outlives recomposition, so it must
    // read the callbacks through state - captured lambdas would keep firing with
    // the skip interval that was current when the button first composed.
    val tap by rememberUpdatedState(onTap)
    val holdActivate by rememberUpdatedState(onHoldActivate)
    val holdTick by rememberUpdatedState(onHoldTick)
    val release by rememberUpdatedState(onRelease)
    val bg = ShuttlTheme.extended.bgTertiary
    val pressedBg = MaterialTheme.colorScheme.surfaceVariant
    val fg = MaterialTheme.colorScheme.onSurface
    val borderColor = MaterialTheme.colorScheme.outline

    Row(
        modifier = modifier
            .background(if (pressed) pressedBg else bg)
            .border(width = 1.dp, color = borderColor)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tap()
                        val holdJob = scope.launch {
                            delay(holdActivationDelayMs)
                            holdActivate()
                            while (isActive) {
                                val tick = holdTick ?: break
                                tick()
                                delay(holdTickPeriodMs)
                            }
                        }
                        try {
                            tryAwaitRelease()
                        } finally {
                            holdJob.cancel()
                            pressed = false
                            release()
                        }
                    },
                )
            }
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = fg,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

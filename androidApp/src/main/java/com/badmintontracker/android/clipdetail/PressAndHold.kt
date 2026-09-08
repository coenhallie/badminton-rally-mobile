package com.badmintontracker.android.clipdetail

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The tap-or-hold gesture every transport control shares: [onTap] on the
 * press itself, so repeated taps stay responsive; [onHoldActivate] once,
 * [holdActivationDelayMs] later, if the finger is still down; [onHoldTick]
 * every [holdTickPeriodMs] after that, or never when it is null; [onRelease]
 * when the finger lifts.
 *
 * The gesture detector is started once and outlives recomposition, so it
 * reads the callbacks through state: captured lambdas would keep firing with
 * the skip interval that was current when the control first composed.
 */
@Composable
internal fun pressAndHold(
    onPressedChange: (Boolean) -> Unit,
    onTap: () -> Unit,
    holdActivationDelayMs: Long = 400L,
    onHoldActivate: () -> Unit = {},
    onHoldTick: (() -> Unit)? = null,
    holdTickPeriodMs: Long = 100L,
    onRelease: () -> Unit = {},
): Modifier {
    val scope = rememberCoroutineScope()
    val pressedChange by rememberUpdatedState(onPressedChange)
    val tap by rememberUpdatedState(onTap)
    val holdActivate by rememberUpdatedState(onHoldActivate)
    val holdTick by rememberUpdatedState(onHoldTick)
    val release by rememberUpdatedState(onRelease)
    return Modifier.pointerInput(Unit) {
        detectTapGestures(
            onPress = {
                pressedChange(true)
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
                    pressedChange(false)
                    release()
                }
            },
        )
    }
}

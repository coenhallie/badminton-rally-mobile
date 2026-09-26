package com.badmintontracker.android.home

import android.provider.Settings
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.android.ui.theme.ShuttlTypeExtras
import com.badmintontracker.shared.home.HeroTicker
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The rotating hero line.
 *
 * Two stacked [Text]s with explicit spacing rather than one Text with a line
 * height: Compose's line height applies to a whole paragraph and cannot
 * tighten below Archivo's natural leading the way stacking two separate
 * lines can, and the mock's hero is 1.08. Stacking sidesteps line height
 * entirely and hits the figure exactly.
 *
 * Mirrors iosApp's HeroTickerView.swift: the same stacking, the same slot
 * sized to the tallest phrase, and the same Reduce Motion rule (the first
 * phrase only, and only its height reserved).
 *
 * The second line is [ShuttlTheme.extended.textMuted], which is the only
 * place that token is allowed: it clears the 3:1 large-text threshold at this
 * size but not the 4.5:1 body threshold, and it is only legible on `bg`.
 */
@Composable
fun HeroTickerView(
    /** Paused while the Add sheet covers the screen (see ClipListScreen's
     * heroPaused): an animation nobody can see still costs a wake per tick. */
    isPaused: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Read once: this is a system setting, not UI state, and re-reading it on
    // every recomposition would mean re-deciding whether to animate mid-scene.
    val reduceMotion = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }

    var index by remember { mutableStateOf(0) }
    var leaving by remember { mutableStateOf(false) }

    LaunchedEffect(isPaused, reduceMotion) {
        // Reduce Motion shows the first phrase and stops. A line of copy that
        // rewrites itself every 2.6 seconds is exactly the motion that setting
        // exists to switch off.
        if (reduceMotion || isPaused) return@LaunchedEffect
        // This effect can be cancelled between `leaving = true` and
        // `leaving = false` (the Add sheet opening mid-transition does exactly
        // that). Without this reset, a cancellation caught there leaves the
        // second line invisible until the next full dwell-and-transition
        // cycle completes.
        leaving = false
        while (isActive) {
            delay(DWELL_MS)
            leaving = true
            delay(TRANSITION_MS)
            index = HeroTicker.next(after = index)
            leaving = false
        }
    }

    val phraseAlpha by animateFloatAsState(
        targetValue = if (leaving) 0f else 1f,
        animationSpec = tween(durationMillis = TRANSITION_MS.toInt()),
        label = "heroPhraseAlpha",
    )
    val phraseOffset by animateDpAsState(
        targetValue = if (leaving) (-12).dp else 0.dp,
        animationSpec = tween(durationMillis = TRANSITION_MS.toInt()),
        label = "heroPhraseOffset",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            // Two Texts read as two separate TalkBack stops by default;
            // merged into one so the hero is announced as a single sentence
            // rather than two.
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(LINE_SPACING),
    ) {
        Text(
            text = HeroTicker.leadLine,
            style = ShuttlTypeExtras.display,
            color = MaterialTheme.colorScheme.onBackground,
        )
        // Sized to the tallest phrase, not the current one: some phrases wrap
        // to two lines on a phone-width screen and some do not, and the empty
        // Matches tab sets body copy straight under the hero, so a box that
        // followed the current phrase would jump that copy a line up and down
        // on every rotation. The invisible copies only take up room; their
        // semantics are cleared so the merged hero still reads as one line.
        // Under Reduce Motion only the first phrase ever shows (see the effect
        // above), so only its height is reserved: room for the others would
        // hold open space for a rotation that coach never sees.
        Box {
            (if (reduceMotion) HeroTicker.phrases.take(1) else HeroTicker.phrases).forEach { phrase ->
                Text(
                    text = phrase,
                    style = ShuttlTypeExtras.display,
                    modifier = Modifier.alpha(0f).clearAndSetSemantics {},
                )
            }
            Text(
                text = HeroTicker.phrases[index],
                style = ShuttlTypeExtras.display,
                color = ShuttlTheme.extended.textMuted,
                modifier = Modifier
                    .alpha(phraseAlpha)
                    .offset(y = phraseOffset),
            )
        }
    }
}

private const val DWELL_MS = 2600L
private const val TRANSITION_MS = 380L

/** 40.sp's display size times the mock's 0.08 leading gap, expressed in dp. */
private val LINE_SPACING = (40 * 0.08).dp

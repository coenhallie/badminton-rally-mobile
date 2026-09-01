package com.badmintontracker.android.localanalysis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.badmintontracker.shared.localvideo.BackgroundWork

/**
 * Ambient background-work state, provided once around the NavHost.
 *
 * A composition local rather than two parameters on five screen signatures.
 * This is app chrome that any screen may show and none of them own, and
 * threading it through would put a progress concern into the signature of every
 * screen that happens to have a bar. [BackgroundWorkAction] keeps its explicit
 * form for tests; screens use the no-argument overload.
 */
val LocalBackgroundWork: ProvidableCompositionLocal<BackgroundWork?> =
    compositionLocalOf { null }

/** Where the indicator sends you: the one screen that already lists runs in detail. */
val LocalBackgroundWorkClick: ProvidableCompositionLocal<() -> Unit> =
    staticCompositionLocalOf { {} }

/** The bar-facing form. Renders nothing unless something is running. */
@Composable
fun BackgroundWorkAction() =
    BackgroundWorkAction(LocalBackgroundWork.current, LocalBackgroundWorkClick.current)

/**
 * The chrome indicator: what is running, on every screen that carries a bar.
 *
 * Absent rather than greyed out when nothing runs, so it costs nothing on the
 * six bars it sits in when the app is idle.
 *
 * The label is the content description rather than only a tooltip, because a
 * progress ring on its own tells a screen reader nothing about which pipeline
 * is running or how far along it is.
 */
@Composable
fun BackgroundWorkAction(
    work: BackgroundWork?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (work == null) return

    IconButton(onClick = onClick, modifier = modifier.semantics { contentDescription = work.label }) {
        Box(contentAlignment = Alignment.Center) {
            if (work.activeCount == 0) {
                // A failure with nothing left running: a ring would imply work
                // is still happening.
                Box(
                    Modifier.size(10.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                )
            } else {
                val ring = Modifier.size(20.dp)
                when (val fraction = work.fraction) {
                    null -> CircularProgressIndicator(modifier = ring, strokeWidth = 2.dp)
                    else -> CircularProgressIndicator({ fraction }, modifier = ring, strokeWidth = 2.dp)
                }
                if (work.hasFailure) {
                    Box(
                        Modifier.size(8.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error)
                            .align(Alignment.BottomEnd),
                    )
                }
            }
        }
    }
}

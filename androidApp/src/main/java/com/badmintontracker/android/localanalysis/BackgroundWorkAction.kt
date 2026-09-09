package com.badmintontracker.android.localanalysis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.badmintontracker.shared.localvideo.BackgroundWork
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column

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

/**
 * The bar-facing form. Renders nothing unless something is running.
 *
 * Tapping opens a sheet saying what each run is doing, rather than navigating.
 * The stages differ in ways a ring cannot express - copying a video, building a
 * background plate and running inference all look like the same spin - and
 * "what is it doing" is the question the indicator provokes.
 */
@Composable
fun BackgroundWorkAction() {
    val work = LocalBackgroundWork.current
    // Read at composition, not inside the callback: a composition local cannot
    // be read from a plain lambda.
    val goToClips = LocalBackgroundWorkClick.current
    var showDetail by remember { mutableStateOf(false) }

    BackgroundWorkAction(work, onClick = { showDetail = true })

    // Dropped when the last run settles, not merely hidden. The sheet is drawn
    // on `showDetail && work != null`, so a flag left set while `work` is null
    // is a sheet that opens itself the moment the next analysis starts, over
    // whatever screen the coach is on. iOS's BackgroundWorkAction clears its
    // own for the same reason.
    LaunchedEffect(work == null) { if (work == null) showDetail = false }

    if (showDetail && work != null) {
        BackgroundWorkSheet(
            work = work,
            onDismiss = { showDetail = false },
            onOpenClips = {
                showDetail = false
                goToClips()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackgroundWorkSheet(
    work: BackgroundWork,
    onDismiss: () -> Unit,
    onOpenClips: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Unset, this resolves M3's surfaceContainerLow, which ShuttlColors.kt
        // never sets. See AddMatchSheet for the full reasoning.
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Working on", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(12.dp))

            work.items.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                        val f = item.fraction
                        if (f == null || f < MIN_DETERMINATE) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            CircularProgressIndicator({ f }, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                    Spacer(Modifier.size(16.dp))
                    Text(item.label, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (work.items.any { it.fraction == null }) {
                // Named, because a spin with no number looks stuck otherwise:
                // before inference can report a fraction the app copies the
                // video out of the gallery and builds TrackNet's background
                // plate by sampling the whole match, and neither has a
                // percentage to give.
                Spacer(Modifier.size(8.dp))
                Text(
                    "Preparing steps have no percentage: the video is copied and a " +
                        "background image of the whole match is built before analysis starts.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.size(16.dp))
            TextButton(onClick = onOpenClips) { Text("Go to matches") }
        }
    }
}

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
                val ring = Modifier.size(RING)
                val fraction = work.fraction
                // The threshold gates the ARC, not the number. Below a couple
                // of percent a determinate ring is its own track and nothing
                // else, so it spins; but a run that has started and is at 0% is
                // a different thing from a run still copying its video, and
                // hiding the number collapsed the two into one long silent
                // spin. Whenever there is a fraction at all, it is shown.
                if (fraction == null || fraction < MIN_DETERMINATE) {
                    CircularProgressIndicator(modifier = ring, strokeWidth = STROKE)
                } else {
                    CircularProgressIndicator({ fraction }, modifier = ring, strokeWidth = STROKE)
                }
                if (fraction != null) {
                    // Inside the ring, so the number and the arc it belongs to
                    // are one object rather than two things to reconcile.
                    Text(
                        text = "${(fraction * 100).toInt()}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
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

/** Big enough to hold two digits legibly, small enough for an app bar action. */
private val RING = 26.dp
private val STROKE = 2.5.dp

/** Under this the arc is invisible, so the ring spins instead of pretending. */
private const val MIN_DETERMINATE = 0.02f

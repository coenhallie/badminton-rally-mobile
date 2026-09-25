package com.badmintontracker.android.clipdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.android.localvideo.formatDuration
import com.badmintontracker.android.ui.icons.ShuttlIcons
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlVideoOverlay
import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.model.LabelColor
import com.badmintontracker.shared.playback.FloatingPanelMath
import com.badmintontracker.shared.playback.LabelLanes
import com.badmintontracker.shared.playback.PanelAnchor
import com.badmintontracker.shared.playback.PanelPosition
import com.badmintontracker.shared.prefs.PlaybackPreferenceRepository
import kotlin.math.roundToInt

/**
 * One label, as a thing to tap while the video plays: a tap tags the moment on
 * screen, with no sheet in between. Drawn in the label's own swatch, as the scoring
 * board's chips are, so the chip and the badge it becomes share a colour.
 */
@Composable
private fun QuickTagChip(label: AnnotationLabel, compact: Boolean = false, onClick: () -> Unit) {
    val swatch = LabelColor.from(label.colorKey)
    val container = swatch?.let { Color(it.background.toInt()) } ?: MaterialTheme.colorScheme.surfaceVariant
    val onContainer = swatch?.let { Color(it.foreground.toInt()) } ?: MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(50),
        color = container,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(role = Role.Button, onClickLabel = "Tag this moment", onClick = onClick),
    ) {
        Text(
            text = label.name,
            // Smaller in the floating window: every pixel of it is a pixel of court
            // the coach cannot see.
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            color = onContainer,
            modifier = Modifier.padding(horizontal = if (compact) 10.dp else 12.dp, vertical = if (compact) 6.dp else 8.dp),
            maxLines = 1,
        )
    }
}

/**
 * The labels under the transport, for tagging without leaving the video: the same
 * note the "+" sheet makes, minus the sheet. The chips sit in at most two lanes
 * that scroll sideways once they are full, the compromise `LabelLanes` explains:
 * the 2026-08-30 label scope design records what one scrolling line costs, and
 * unlimited wrapping made an account with many clip labels pay for them in video
 * and notes on every rally.
 *
 * Absent rather than empty when there is nothing to tap, so a viewer of somebody
 * else's match does not get a heading over nothing.
 */
@Composable
fun ReplayLabelRow(
    labels: List<AnnotationLabel>,
    message: String?,
    onTag: (AnnotationLabel) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (labels.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        LabelLanesStrip(labels, onTag)
        // One line that is always there: what a tap does, until a tap has
        // happened, then what it did. Swapping text in a held line rather than
        // adding one keeps the notes underneath from jumping on every tag.
        Text(
            message ?: "Tap a label to tag this moment",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
        )
    }
}

/** The page gutter the strip rests in, and scrolls out of. */
private val STRIP_GUTTER = 24.dp
private val STRIP_GAP = 8.dp

/**
 * The chips, dealt into lanes by the shared `LabelLanes` and scrolled sideways
 * when the wider lane outgrows the row. Centred under the transport while it
 * fits, as the wrapping row was; from the leading edge once it scrolls, so the
 * first label is where a reader starts. The gutter is inside the scroll, so the
 * strip rests at the page margin and the last chip clears it at the far end.
 */
@Composable
private fun LabelLanesStrip(
    labels: List<AnnotationLabel>,
    onTag: (AnnotationLabel) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        // The scroll below gives its content no width to fit to; the box does.
        val availablePx = with(density) { (maxWidth - STRIP_GUTTER * 2).roundToPx() }
        val gapPx = with(density) { STRIP_GAP.roundToPx() }
        Layout(
            content = { labels.forEach { label -> QuickTagChip(label, onClick = { onTag(label) }) } },
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = STRIP_GUTTER),
        ) { measurables, _ ->
            val placeables = measurables.map { it.measure(Constraints()) }
            val fit = LabelLanes.fit(placeables.map { it.width.toFloat() }, gapPx.toFloat(), availablePx.toFloat())
            val stripWidth = fit.width.roundToInt()
            val laneHeight = placeables.maxOf { it.height }
            val width = maxOf(stripWidth, availablePx)
            val lead = (width - stripWidth) / 2
            layout(width, fit.lanes * laneHeight + (fit.lanes - 1) * gapPx) {
                placeables.forEachIndexed { index, placeable ->
                    val at = fit.placements[index]
                    placeable.place(lead + at.x.roundToInt(), at.lane * (laneHeight + gapPx))
                }
            }
        }
    }
}

/**
 * The floating window, placed in a fullscreen player's box. Here rather than in
 * each screen because both players that tag - the rally replay and the local
 * video - host it identically: it measures the box it floats in, keeps clear of a
 * display cutout, of the card's chips and of the transport, and reads and writes
 * where it was left.
 */
@Composable
fun BoxScope.FullscreenLabels(
    labels: List<AnnotationLabel>,
    prefs: PlaybackPreferenceRepository,
    message: String?,
    onTag: (AnnotationLabel) -> Unit,
) {
    if (labels.isEmpty()) return
    var area by remember { mutableStateOf(IntSize.Zero) }
    val anchor by prefs.labelPanelAnchor.collectAsStateWithLifecycle()
    Box(
        modifier = Modifier
            .matchParentSize()
            .windowInsetsPadding(WindowInsets.displayCutout)
            // The window may go anywhere except over the video card's chips or the
            // transport: it is drawn above both, so parked there it would take the
            // taps meant for play, or for the way out of fullscreen.
            .padding(top = CHIP_CLEARANCE, bottom = TRANSPORT_CLEARANCE)
            .onSizeChanged { area = it },
    ) {
        FloatingLabelPanel(
            labels = labels,
            area = area,
            anchor = anchor,
            onAnchorChange = prefs::setLabelPanelAnchor,
            onTag = onTag,
            message = message,
        )
    }
}

/** "Tagged Good shot at 0:12": what both the row and the window say after a tap. */
fun quickTagConfirmation(label: AnnotationLabel, positionMs: Long): String =
    "Tagged ${label.name} at ${formatDuration(positionMs.coerceAtLeast(0L))}"

/**
 * The same chips over a fullscreen video, in a window the coach can move: there is
 * no "under the transport" in fullscreen, and wherever a fixed panel went it would
 * sit on somebody's half of the court.
 *
 * A sibling of the video in the fullscreen box, never a child of [VideoCard]'s
 * overlay slot: that slot scales and pans with the frame, and its box owns the pan
 * gesture a drag would fight. Dragged by its title bar only, so a chip is always a
 * tap. Where it is left is remembered as a [PanelAnchor], a fraction of its travel,
 * so it comes back on screen when the screen or the chips change size.
 *
 * [message] is the last thing that happened, said inside the window because the
 * page's snackbar is underneath the fullscreen box and cannot be seen.
 *
 * @param area the size of the box this floats in, in pixels.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FloatingLabelPanel(
    labels: List<AnnotationLabel>,
    area: IntSize,
    anchor: PanelAnchor,
    onAnchorChange: (PanelAnchor) -> Unit,
    onTag: (AnnotationLabel) -> Unit,
    message: String?,
    modifier: Modifier = Modifier,
) {
    if (labels.isEmpty() || area == IntSize.Zero) return

    var collapsed by rememberSaveable { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    // Null until a drag starts: until then the window sits where its anchor says,
    // which is what keeps it on screen while it is still being measured.
    var dragged by remember { mutableStateOf<PanelPosition?>(null) }
    // Where the window was when it was folded or unfolded. Its anchor is a fraction
    // of its travel, and folding changes its height and so its travel: left to the
    // anchor, the title bar would jump out from under the finger that tapped it.
    // Held until the new size is measured, then turned back into an anchor.
    var held by remember { mutableStateOf<PanelPosition?>(null) }
    val currentSize by rememberUpdatedState(size)
    val currentArea by rememberUpdatedState(area)
    val currentAnchor by rememberUpdatedState(anchor)
    val reportAnchor by rememberUpdatedState(onAnchorChange)

    fun clamped(position: PanelPosition) = FloatingPanelMath.clamp(
        position, currentSize.width.toFloat(), currentSize.height.toFloat(),
        currentArea.width.toFloat(), currentArea.height.toFloat(),
    )
    val position = clamped(
        dragged ?: held ?: FloatingPanelMath.position(
            anchor, size.width.toFloat(), size.height.toFloat(), area.width.toFloat(), area.height.toFloat(),
        ),
    )

    // Let go only once the anchor that replaced it has arrived, or there is a frame
    // in between drawn from the old anchor at the new size.
    LaunchedEffect(anchor) { held = null }

    Column(
        modifier = modifier
            .offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
            .width(PANEL_WIDTH)
            .onSizeChanged { measured ->
                size = measured
                held?.let { kept ->
                    reportAnchor(
                        FloatingPanelMath.anchor(
                            kept, measured.width.toFloat(), measured.height.toFloat(),
                            currentArea.width.toFloat(), currentArea.height.toFloat(),
                        ),
                    )
                }
            }
            .clip(RoundedCornerShape(ShuttlRadius.large))
            .background(ShuttlVideoOverlay.scrim),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            dragged = FloatingPanelMath.position(
                                currentAnchor, currentSize.width.toFloat(), currentSize.height.toFloat(),
                                currentArea.width.toFloat(), currentArea.height.toFloat(),
                            )
                        },
                        onDragEnd = {
                            dragged?.let {
                                reportAnchor(
                                    FloatingPanelMath.anchor(
                                        it, currentSize.width.toFloat(), currentSize.height.toFloat(),
                                        currentArea.width.toFloat(), currentArea.height.toFloat(),
                                    ),
                                )
                            }
                            dragged = null
                        },
                        onDragCancel = { dragged = null },
                        onDrag = { change, amount ->
                            change.consume()
                            dragged = clamped(PanelPosition((dragged?.x ?: 0f) + amount.x, (dragged?.y ?: 0f) + amount.y))
                        },
                    )
                }
                .semantics { contentDescription = "Labels window. Drag to move." }
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(ShuttlIcons.Grip, contentDescription = null, tint = ShuttlVideoOverlay.text.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
            Text(
                "Labels",
                style = MaterialTheme.typography.labelMedium,
                color = ShuttlVideoOverlay.text,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            Icon(
                if (collapsed) ShuttlIcons.ChevronDown else ShuttlIcons.ChevronUp,
                contentDescription = if (collapsed) "Show labels" else "Hide labels",
                tint = ShuttlVideoOverlay.text,
                modifier = Modifier
                    .clip(RoundedCornerShape(ShuttlRadius.pill))
                    .clickable(role = Role.Button) {
                        held = position
                        collapsed = !collapsed
                    }
                    .padding(12.dp)
                    .size(14.dp),
            )
        }
        if (!collapsed) {
            // Capped and scrollable, so a long palette cannot grow the window past
            // the screen it has to stay inside.
            FlowRow(
                // The padding is outside the scroll, not inside it, so the viewport is
                // whole rows and nothing else.
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    .heightIn(max = PANEL_MAX_CHIPS_HEIGHT)
                    .verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                labels.forEach { label -> QuickTagChip(label, compact = true, onClick = { onTag(label) }) }
            }
            // Always there while the window is open, blank until there is something
            // to say: a line that came and went would change the window's height on
            // every tag, and with it where the window sits.
            Text(
                message.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = ShuttlVideoOverlay.text,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            )
        }
    }
}

/**
 * What the window says after a tap, and for how long. "Tagged Good shot at 0:12"
 * for [QUICK_TAG_MESSAGE_MS], then nothing: long enough to read at a glance, short
 * enough not to be there for the next rally.
 */
@Composable
fun rememberQuickTagMessage(): QuickTagMessage {
    val holder = remember { QuickTagMessage() }
    LaunchedEffect(holder.text, holder.stamp) {
        if (holder.text == null) return@LaunchedEffect
        kotlinx.coroutines.delay(QUICK_TAG_MESSAGE_MS)
        holder.text = null
    }
    return holder
}

class QuickTagMessage {
    var text by mutableStateOf<String?>(null)
        internal set
    /** Bumped on every show, so the same text twice in a row restarts the clock. */
    internal var stamp by mutableStateOf(0)

    fun show(message: String) {
        text = message
        stamp += 1
    }
}

// The video card's chips: their 12 dp inset, their height, and 8 dp of air.
private val CHIP_CLEARANCE = 44.dp
// The fullscreen transport: its 24 dp bottom inset, the 60 dp play circle, and 12 dp of air.
private val TRANSPORT_CLEARANCE = 96.dp
private val PANEL_WIDTH = 236.dp
// Four rows of compact chips (26 dp each) and the three 6 dp gaps between them, so
// the cut falls between rows rather than through one.
private val PANEL_MAX_CHIPS_HEIGHT = 122.dp
private const val QUICK_TAG_MESSAGE_MS = 2_500L

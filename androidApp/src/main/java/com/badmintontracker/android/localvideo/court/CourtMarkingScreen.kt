package com.badmintontracker.android.localvideo.court

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.android.localanalysis.AnalysisTarget
import com.badmintontracker.android.localanalysis.MetricSelector
import com.badmintontracker.android.ui.components.ErrorBanner
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.android.ui.theme.ShuttlRadius
import com.badmintontracker.android.ui.theme.ShuttlTheme
import com.badmintontracker.shared.local.AnalysisMetric
import com.badmintontracker.shared.local.DeviceThroughput
import com.badmintontracker.shared.localvideo.court.CourtMarkingSpec
import com.badmintontracker.shared.localvideo.court.CourtMarkingState
import com.badmintontracker.shared.model.CourtKeypoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Setting a video up for analysis, as two steps.
 *
 * Step one is the 12-point court calibration, behavior-identical to desktop
 * CourtSetup.vue: same point order/labels/colors, same overlays, same
 * display->source-pixel mapping. Mobile-only affordance: pinch-zoom/pan (never
 * changes the output). Step two is what the run should produce.
 *
 * The two were one screen, and the Rally Setup mock is what says they are not:
 * a portrait video, twelve markers, a court legend, three metric options with
 * their own time costs and two action buttons do not co-exist on a phone
 * without something being pushed off. Split, each step is a page with one
 * question on it and one button that answers it.
 *
 * Deliberately two steps of one route rather than two routes. The keypoints,
 * the frame count and the frame rate are all read from the same decoded frame
 * and all needed by step two; a second destination would either re-decode the
 * video or carry them through the back stack, and starting an analysis would
 * have to pop twice. See AuthGate's Route.CourtMarking, whose single
 * popBackStack is load-bearing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourtMarkingScreen(
    vm: CourtMarkingViewModel,
    throughput: DeviceThroughput,
    onStartAnalysis: (CourtKeypoints, AnalysisTarget, Set<AnalysisMetric>) -> Unit,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var savedStep by rememberSaveable { mutableStateOf(SetupStep.Mapping) }
    // Clips only by default: it is the one metric every user came for, and
    // pose roughly doubles the wait, so it is opted into rather than out of.
    var metrics by rememberSaveable(
        saver = listSaver(
            save = { it.value.map(AnalysisMetric::name) },
            restore = { mutableStateOf(it.map(AnalysisMetric::valueOf).toSet()) },
        ),
    ) { mutableStateOf(setOf(AnalysisMetric.RALLY_CLIPS)) }

    // A restored step cannot outrank the marking it was reached from. The step
    // survives process death in the saved-state bundle and the twelve points do
    // not: the view model holds them and has no SavedStateHandle, so a phone
    // that reclaims the process while step two is open comes back to an empty
    // marking. Deriving the step rather than trusting the saved one means the
    // second page can never be drawn over one, where either Analyse button
    // would have taken toCourtKeypoints()'s incomplete check straight to a
    // crash.
    val marking = state.marking
    val step = if (marking?.isComplete == true) savedStep else SetupStep.Mapping

    // The system back gesture unwinds the steps before it leaves the screen,
    // which is what the bar's own back arrow does.
    BackHandler(enabled = step == SetupStep.Options) { savedStep = SetupStep.Mapping }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { StepTitle(step) },
                navigationIcon = {
                    IconButton(
                        onClick = { if (step == SetupStep.Options) savedStep = SetupStep.Mapping else onBack() },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { StepDots(step) },
            )
        },
    ) { padding ->
        // No scrolling at this level. Each step owns its own scrolling region
        // and pins its buttons outside it, so the button that ends a step is
        // always on screen, portrait videos and long metric lists included.
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            val error = state.error
            when {
                error != null -> Box(Modifier.padding(PagePadding)) { ErrorBanner(error) }
                marking == null -> Box(
                    Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                step == SetupStep.Mapping -> MappingStep(
                    vm = vm,
                    marking = marking,
                    frame = state.frame,
                    onContinue = { savedStep = SetupStep.Options },
                )
                else -> OptionsStep(
                    frames = state.frameCount,
                    fps = state.fps,
                    throughput = throughput,
                    metrics = metrics,
                    onToggle = { metric ->
                        metrics = if (metric in metrics) metrics - metric else metrics + metric
                    },
                    onStart = { target -> onStartAnalysis(marking.toCourtKeypoints(), target, metrics) },
                )
            }
        }
    }
}

/** Which of the two setup steps is on screen. */
private enum class SetupStep(val title: String, val label: String) {
    Mapping("Court mapping", "Step 1 of 2"),
    Options("Analysis", "Step 2 of 2"),
}

/** The bar's two lines: what this step is, and where it sits in the pair. */
@Composable
private fun StepTitle(step: SetupStep) {
    Column {
        Text(
            step.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            step.label,
            style = MaterialTheme.typography.bodySmall,
            color = ShuttlTheme.extended.textTertiary,
            maxLines = 1,
        )
    }
}

/** The mock's pair of bars, the reached ones in the accent. */
@Composable
private fun StepDots(step: SetupStep) {
    Row(
        modifier = Modifier.padding(end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        SetupStep.entries.forEach { s ->
            Box(
                Modifier
                    .size(width = 18.dp, height = 3.dp)
                    .clip(RoundedCornerShape(ShuttlRadius.pill))
                    .background(
                        if (s.ordinal <= step.ordinal) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                    ),
            )
        }
    }
}

// --- Step one: the twelve points ---------------------------------------------

@Composable
private fun ColumnScope.MappingStep(
    vm: CourtMarkingViewModel,
    marking: CourtMarkingState,
    frame: android.graphics.Bitmap?,
    onContinue: () -> Unit,
) {
    // The frame takes the height its own aspect ratio asks for and no more,
    // capped at most of the region so a portrait video cannot squeeze the
    // guidance out; the guidance takes what is left and scrolls. Neither can
    // push the button off screen, which was the original bug: everything here
    // was unweighted, the total exceeded the screen once the metric selector
    // was added, and the second action button was clipped away under the
    // navigation bar. The selector has its own page now.
    //
    // Whatever height is left over collects between the guidance and the
    // button, the way the mock's own `margin-top: auto` collects it, rather
    // than being split above and below a floating frame.
    BoxWithConstraints(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
    ) {
        val frameMax = maxHeight * FRAME_MAX_SHARE
        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = frameMax)
                    .padding(horizontal = PagePadding),
                contentAlignment = Alignment.TopCenter,
            ) {
                FrameWithOverlay(vm = vm, marking = marking, frame = frame)
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                InstructionRow(marking)
                PlacementProgress(placed = marking.points.size)
                SchematicCourtGuide(nextIndex = marking.nextIndex, placedCount = marking.points.size)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PagePadding, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ShuttlButton(
                        text = "Undo",
                        onClick = vm::onUndo,
                        variant = ShuttlButtonVariant.Secondary,
                        enabled = marking.points.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    )
                    ShuttlButton(
                        text = "Clear",
                        onClick = vm::onClear,
                        variant = ShuttlButtonVariant.Secondary,
                        enabled = marking.points.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    // Drawn whatever the count, and disabled until the twelve are down, rather
    // than appearing on the twelfth tap: a button that materialises under a
    // finger is how the wrong thing gets pressed, and toCourtKeypoints()
    // throws on an incomplete marking.
    ShuttlButton(
        text = "Continue",
        onClick = onContinue,
        variant = ShuttlButtonVariant.Primary,
        enabled = marking.isComplete,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PagePadding)
            .padding(bottom = 16.dp),
    )
}

@Composable
private fun InstructionRow(marking: CourtMarkingState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PagePadding)
            .padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!marking.isComplete) {
            // Named, and in the point's own colour: twelve landmarks with
            // names as close as "Service Near-Left" and "Service Far-Left"
            // are told apart by which one the app is asking for.
            Text(
                text = "Tap: ${CourtMarkingSpec.fullLabels[marking.nextIndex]}",
                style = MaterialTheme.typography.titleLarge,
                color = Color(CourtMarkingSpec.colors[marking.nextIndex]),
                modifier = Modifier.weight(1f),
            )
        } else {
            Text(
                text = "All points placed",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = "${marking.points.size} / ${CourtMarkingSpec.TOTAL} points",
            style = MaterialTheme.typography.bodySmall,
            color = ShuttlTheme.extended.textTertiary,
        )
    }
}

/** The mock's hairline bar under the count: how much of the marking is done. */
@Composable
private fun PlacementProgress(placed: Int) {
    val shape = RoundedCornerShape(ShuttlRadius.pill)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PagePadding)
            .padding(top = 12.dp)
            .height(3.dp)
            .clip(shape)
            .background(ShuttlTheme.extended.bgTertiary),
    ) {
        Box(
            Modifier
                .fillMaxWidth(placed.toFloat() / CourtMarkingSpec.TOTAL)
                .fillMaxHeight()
                .clip(shape)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun FrameWithOverlay(
    vm: CourtMarkingViewModel,
    marking: CourtMarkingState,
    frame: android.graphics.Bitmap?,
) {
    // MutableState (not plain values) so the pointerInput(Unit) closures below
    // always read fresh values instead of stale captures.
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var layoutSize by remember { mutableStateOf(IntSize.Zero) }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // aspectRatio inside bounded constraints picks the largest fitting box:
    // width-limited for landscape videos, height-limited for portrait ones.
    Box(
        modifier = Modifier
            .aspectRatio(marking.videoWidth.toFloat() / marking.videoHeight.toFloat())
            // The mock's rounded frame. clip, not clipToBounds: the corners are
            // the point, and the zoom transform below is clipped by both alike.
            .clip(RoundedCornerShape(ShuttlRadius.large))
            .onSizeChanged { layoutSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 6f)
                    val effectiveZoom = newScale / scale
                    // Zoom around the gesture centroid, then apply the pan.
                    offset = centroid - (centroid - offset) * effectiveZoom + pan
                    scale = newScale
                    if (scale == 1f) offset = Offset.Zero
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    // Inverse of the graphicsLayer transform (origin top-left).
                    val x = (tap.x - offset.x) / scale
                    val y = (tap.y - offset.y) / scale
                    if (x in 0f..layoutSize.width.toFloat() && y in 0f..layoutSize.height.toFloat()) {
                        vm.onTap(x, y, layoutSize.width.toFloat(), layoutSize.height.toFloat())
                    }
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        ) {
            frame?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Video frame for court mapping",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            val markerLabelFontFamily = MaterialTheme.typography.labelSmall.fontFamily
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCourtGuide()
                drawCornerRectangle(marking)
                drawPlacedPoints(marking, scale, density.density, textMeasurer, markerLabelFontFamily)
            }
        }
    }
}

// --- Step two: what the run should produce ------------------------------------

@Composable
private fun ColumnScope.OptionsStep(
    frames: Int,
    fps: Double,
    throughput: DeviceThroughput,
    metrics: Set<AnalysisMetric>,
    onToggle: (AnalysisMetric) -> Unit,
    onStart: (AnalysisTarget) -> Unit,
) {
    // There is no frame on this step, so nothing has to flex: the page is a
    // plain scrolling column with the two actions pinned under it.
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PagePadding),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            "What to analyze",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "Pick what this run should produce.",
            style = MaterialTheme.typography.bodySmall,
            color = ShuttlTheme.extended.textTertiary,
            modifier = Modifier.padding(top = 8.dp),
        )
        MetricSelector(
            frames = frames,
            fps = fps,
            selected = metrics,
            throughput = throughput,
            onToggle = onToggle,
            modifier = Modifier.padding(top = 22.dp),
        )
        Spacer(Modifier.height(16.dp))
    }

    // Two buttons rather than one with a toggle: the point is to run the same
    // video through both pipelines back to back and compare, and a toggle adds
    // a step to every comparison.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PagePadding)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ShuttlButton(
            text = AnalysisTarget.Cloud.label,
            onClick = { onStart(AnalysisTarget.Cloud) },
            variant = ShuttlButtonVariant.Primary,
            modifier = Modifier.fillMaxWidth(),
        )
        ShuttlButton(
            text = AnalysisTarget.Device.label,
            onClick = { onStart(AnalysisTarget.Device) },
            variant = ShuttlButtonVariant.Secondary,
            enabled = metrics.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// --- Frame overlay drawing (desktop drawCourtGuide / drawOverlay parity) -----

private val GuideGreen = Color(0x3322C55E)
private val ConnectGreen = Color(0x9922C55E)

/** Semi-transparent dashed guide: court rect at 15% margins, net, service, center lines. */
private fun DrawScope.drawCourtGuide() {
    val dash = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
    val w = size.width
    val h = size.height
    val margin = 0.15f
    val x1 = w * margin
    val y1 = h * margin
    val x2 = w * (1 - margin)
    val y2 = h * (1 - margin)

    fun line(a: Offset, b: Offset) =
        drawLine(GuideGreen, a, b, strokeWidth = 1.dp.toPx(), pathEffect = dash)

    // Court rectangle
    line(Offset(x1, y1), Offset(x2, y1))
    line(Offset(x2, y1), Offset(x2, y2))
    line(Offset(x2, y2), Offset(x1, y2))
    line(Offset(x1, y2), Offset(x1, y1))
    // Net line
    line(Offset(x1, h / 2), Offset(x2, h / 2))
    // Service lines (60% between boundary and net, like desktop)
    val serviceY1 = y1 + (h / 2 - y1) * 0.6f
    val serviceY2 = y2 - (y2 - h / 2) * 0.6f
    line(Offset(x1, serviceY1), Offset(x2, serviceY1))
    line(Offset(x1, serviceY2), Offset(x2, serviceY2))
    // Center line
    line(Offset(w / 2, serviceY1), Offset(w / 2, serviceY2))
}

/** Dashed rectangle connecting the first 4 corners once placed (desktop parity). */
private fun DrawScope.drawCornerRectangle(marking: CourtMarkingState) {
    if (marking.points.size < 4) return
    val toDisplay = displayFactor(marking)
    val corners = marking.points.take(4).map { Offset(it.x * toDisplay.x, it.y * toDisplay.y) }
    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 4f))
    for (i in corners.indices) {
        drawLine(
            ConnectGreen,
            corners[i],
            corners[(i + 1) % corners.size],
            strokeWidth = 2.dp.toPx(),
            pathEffect = dash,
        )
    }
}

private fun DrawScope.drawPlacedPoints(
    marking: CourtMarkingState,
    zoom: Float,
    density: Float,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    labelFontFamily: androidx.compose.ui.text.font.FontFamily?,
) {
    val toDisplay = displayFactor(marking)
    // Constant on-screen size regardless of pinch-zoom (markers are presentation only).
    val radius = 10f * density / zoom
    marking.points.forEachIndexed { i, p ->
        val center = Offset(p.x * toDisplay.x, p.y * toDisplay.y)
        drawCircle(Color(CourtMarkingSpec.colors[i]), radius, center)
        drawCircle(Color.Black, radius, center, style = Stroke(width = 2f * density / zoom))
        val label = textMeasurer.measure(
            CourtMarkingSpec.shortLabels[i],
            // Only fontFamily is taken from the type scale: letterSpacing and
            // lineHeight there are pinned to labelSmall's 11sp role, and this
            // marker's fontSize tracks pinch-zoom, so inheriting them would pin
            // spacing/line-height while the glyph shrinks and throw off the
            // width/2, height/2 centering below.
            TextStyle(
                fontFamily = labelFontFamily,
                fontSize = (9f / zoom).sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
            ),
        )
        drawText(
            label,
            topLeft = Offset(center.x - label.size.width / 2f, center.y - label.size.height / 2f),
        )
    }
}

/** Source-pixel -> display-pixel factors for the frame box. */
private fun DrawScope.displayFactor(marking: CourtMarkingState) =
    Offset(size.width / marking.videoWidth, size.height / marking.videoHeight)

// --- Schematic court guide (replaces desktop's 280px MiniCourt side panel) ---

// Real court proportions from badminton-tracker homography.ts.
private const val COURT_W = 6.1f
private const val COURT_L = 13.4f
private const val SERVICE_LINE = 1.98f

private val schematicPositions: List<Pair<Float, Float>> = listOf(
    0f to 0f, COURT_W to 0f, COURT_W to COURT_L, 0f to COURT_L,               // TL TR BR BL
    0f to COURT_L / 2, COURT_W to COURT_L / 2,                                // NL NR
    0f to COURT_L / 2 - SERVICE_LINE, COURT_W to COURT_L / 2 - SERVICE_LINE,  // SNL SNR
    0f to COURT_L / 2 + SERVICE_LINE, COURT_W to COURT_L / 2 + SERVICE_LINE,  // SFL SFR
    COURT_W / 2 to COURT_L / 2 - SERVICE_LINE, COURT_W / 2 to COURT_L / 2 + SERVICE_LINE, // CTN CTF
)

@Composable
private fun SchematicCourtGuide(nextIndex: Int, placedCount: Int) {
    val outline = MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PagePadding)
            .padding(top = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(
            modifier = Modifier
                .height(SCHEMATIC_HEIGHT)
                .width(SCHEMATIC_HEIGHT * (COURT_W / COURT_L)),
        ) {
            val px = size.width / COURT_W
            fun at(pos: Pair<Float, Float>) = Offset(pos.first * px, pos.second * px)

            // Court outline + lines
            drawRect(outline, style = Stroke(width = 1.dp.toPx()))
            drawLine(outline, at(0f to COURT_L / 2), at(COURT_W to COURT_L / 2), 1.dp.toPx())
            drawLine(
                outline,
                at(0f to COURT_L / 2 - SERVICE_LINE),
                at(COURT_W to COURT_L / 2 - SERVICE_LINE),
                1.dp.toPx(),
            )
            drawLine(
                outline,
                at(0f to COURT_L / 2 + SERVICE_LINE),
                at(COURT_W to COURT_L / 2 + SERVICE_LINE),
                1.dp.toPx(),
            )
            drawLine(
                outline,
                at(COURT_W / 2 to COURT_L / 2 - SERVICE_LINE),
                at(COURT_W / 2 to COURT_L / 2 + SERVICE_LINE),
                1.dp.toPx(),
            )

            schematicPositions.forEachIndexed { i, pos ->
                val placed = i < placedCount
                val isNext = i == nextIndex
                val r = if (isNext) 6.dp.toPx() else 3.5f.dp.toPx()
                val color = Color(CourtMarkingSpec.colors[i])
                drawCircle(if (placed || isNext) color else color.copy(alpha = 0.35f), r, at(pos))
                if (isNext) drawCircle(Color.Black, r, at(pos), style = Stroke(1.dp.toPx()))
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Tap each court landmark in the order shown.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Twelve points give precise homography for player tracking, speeds and zones. Pinch to zoom for accuracy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- Frame extraction ---------------------------------------------------------

/** Desktop parity: the frame at t=0.1s (avoids a black first frame), source resolution. */
suspend fun loadFirstFrame(context: Context, uri: Uri): CourtFrame =
    withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val bmp = retriever.getFrameAtTime(100_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: error("Couldn't extract video frame")
            val fps = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toDoubleOrNull()
                ?.takeIf { it > 0 }
                ?: DEFAULT_FPS
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            CourtFrame(
                frame = bmp,
                width = bmp.width,
                height = bmp.height,
                fps = fps,
                frameCount = (durationMs / 1000.0 * fps).toInt(),
            )
        } finally {
            retriever.release()
        }
    }

/** Only when the container does not say; most do. */
private const val DEFAULT_FPS = 30.0

/** The mock's page margin, and the app's elsewhere. */
private val PagePadding = 24.dp

/**
 * The legend's court. Smaller than the 160dp it was: it shares the guidance
 * area with the instruction, the progress bar and the two edit buttons, and
 * that area is now what is left after the frame.
 */
private val SCHEMATIC_HEIGHT = 128.dp

/**
 * The most of the step the frame may take.
 *
 * Only a portrait video ever reaches it: a landscape one is width-limited well
 * under this. The remainder is the guidance's, which scrolls, so the cap is
 * what stops a tall video from pushing the instruction and the point count out
 * of sight.
 */
private const val FRAME_MAX_SHARE = 0.62f

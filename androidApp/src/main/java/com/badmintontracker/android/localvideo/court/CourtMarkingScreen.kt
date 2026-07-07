package com.badmintontracker.android.localvideo.court

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.badmintontracker.android.ui.components.ErrorBanner
import com.badmintontracker.android.ui.components.ShuttlButton
import com.badmintontracker.android.ui.components.ShuttlButtonVariant
import com.badmintontracker.shared.model.CourtKeypoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 12-point court calibration, behavior-identical to desktop CourtSetup.vue:
 * same point order/labels/colors, same overlays, same display->source-pixel
 * mapping. Mobile-only affordance: pinch-zoom/pan (never changes the output).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourtMarkingScreen(
    vm: CourtMarkingViewModel,
    onStartAnalysis: (CourtKeypoints) -> Unit,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "COURT MAPPING",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 14.sp),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        // No scrolling: the frame flexes to the leftover space (weight), so the
        // controls below are always on screen — portrait videos included.
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            val error = state.error
            val marking = state.marking
            when {
                error != null -> Box(Modifier.padding(16.dp)) { ErrorBanner(error) }
                marking == null -> Box(
                    Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                else -> MarkingContent(
                    vm = vm,
                    marking = marking,
                    onStartAnalysis = onStartAnalysis,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.MarkingContent(
    vm: CourtMarkingViewModel,
    marking: CourtMarkingState,
    onStartAnalysis: (CourtKeypoints) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()

    // The frame flexes to whatever height is left after the pinned controls,
    // so it can never push them off screen (the original bug on portrait video).
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        FrameWithOverlay(vm = vm, marking = marking, frame = state.frame)
    }

    InstructionRow(marking)
    SchematicCourtGuide(nextIndex = marking.nextIndex, placedCount = marking.points.size)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
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
    if (marking.isComplete) {
        ShuttlButton(
            text = "Start Analysis",
            onClick = { onStartAnalysis(marking.toCourtKeypoints()) },
            variant = ShuttlButtonVariant.Primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
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
            .clipToBounds()
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
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCourtGuide()
                drawCornerRectangle(marking)
                drawPlacedPoints(marking, scale, density.density, textMeasurer)
            }
        }
    }
}

@Composable
private fun InstructionRow(marking: CourtMarkingState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!marking.isComplete) {
            Text(
                text = "Tap: ${CourtMarkingSpec.fullLabels[marking.nextIndex]}",
                style = MaterialTheme.typography.titleMedium,
                color = Color(CourtMarkingSpec.colors[marking.nextIndex]),
                modifier = Modifier.weight(1f),
            )
        } else {
            Text(
                text = "All points placed",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = "${marking.points.size} / ${CourtMarkingSpec.TOTAL} points",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            TextStyle(fontSize = (9f / zoom).sp, fontWeight = FontWeight.Bold, color = Color.Black),
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
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(
            modifier = Modifier
                .height(160.dp)
                .width(160.dp * (COURT_W / COURT_L)),
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
                "12 points give precise homography for player tracking, speeds and zones. Pinch to zoom for accuracy.",
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
            CourtFrame(bmp, bmp.width, bmp.height)
        } finally {
            retriever.release()
        }
    }

package com.badmintontracker.android.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Lucide-style outlined icons inlined as [ImageVector]s so the app doesn't
 * pull in `androidx.compose.material:material-icons-extended` (~2-3 MB APK
 * cost). Path data is taken verbatim from the web app reference
 * `badminton-tracker/src/views/LoginView.vue`.
 *
 * Stroke is a solid color so Icon's ColorFilter.tint can recolor it via
 * SrcIn — Color.Unspecified renders transparent and would not be tinted.
 */
object ShuttlIcons {
    val Sun: ImageVector by lazy { buildSun() }
    val Moon: ImageVector by lazy { buildMoon() }
    val Info: ImageVector by lazy { buildInfo() }
    // The empty-state glyphs, one per list that can have nothing in it. All
    // Lucide, all at Lucide's stock 2-unit stroke rather than the 2.5 the sun
    // and moon carry over from the web app: these are only ever drawn at 28dp
    // inside ShuttlEmptyState's disc, where the heavier line reads as a blob.
    val Trophy: ImageVector by lazy { buildLucide("ShuttlTrophy") { trophy() } }
    val ChartColumn: ImageVector by lazy { buildLucide("ShuttlChartColumn") { chartColumn() } }
    val Video: ImageVector by lazy { buildLucide("ShuttlVideo") { video() } }
    val ListOrdered: ImageVector by lazy { buildLucide("ShuttlListOrdered") { listOrdered() } }
    val Tag: ImageVector by lazy { buildLucide("ShuttlTag") { tag() } }

    // The transport glyphs, traced from the Rally Analysis mock's own SVGs:
    // 14-unit chevrons and steps at a 1.4 stroke, an 18-unit filled play and
    // pause. Kept as one family so the five buttons of the skeleton view's
    // transport row share a stroke weight.
    val ChevronsLeft: ImageVector by lazy { buildStroked("ShuttlChevronsLeft") { moveTo(8f, 3f); lineTo(3.5f, 7f); lineTo(8f, 11f); moveTo(12f, 3f); lineTo(7.5f, 7f); lineTo(12f, 11f) } }
    val ChevronsRight: ImageVector by lazy { buildStroked("ShuttlChevronsRight") { moveTo(6f, 3f); lineTo(10.5f, 7f); lineTo(6f, 11f); moveTo(2f, 3f); lineTo(6.5f, 7f); lineTo(2f, 11f) } }
    val StepBack: ImageVector by lazy { buildStroked("ShuttlStepBack") { moveTo(9.5f, 3f); lineTo(5.5f, 7f); lineTo(9.5f, 11f); moveTo(3.5f, 3f); lineTo(3.5f, 11f) } }
    val StepForward: ImageVector by lazy { buildStroked("ShuttlStepForward") { moveTo(4.5f, 3f); lineTo(8.5f, 7f); lineTo(4.5f, 11f); moveTo(10.5f, 3f); lineTo(10.5f, 11f) } }
    // Corner brackets pointing out, and in: the fullscreen toggle on the video card.
    val Maximize: ImageVector by lazy { buildStroked("ShuttlMaximize") { moveTo(2f, 5.5f); lineTo(2f, 2f); lineTo(5.5f, 2f); moveTo(8.5f, 2f); lineTo(12f, 2f); lineTo(12f, 5.5f); moveTo(12f, 8.5f); lineTo(12f, 12f); lineTo(8.5f, 12f); moveTo(5.5f, 12f); lineTo(2f, 12f); lineTo(2f, 8.5f) } }
    val Minimize: ImageVector by lazy { buildStroked("ShuttlMinimize") { moveTo(2f, 5.5f); lineTo(5.5f, 5.5f); lineTo(5.5f, 2f); moveTo(8.5f, 2f); lineTo(8.5f, 5.5f); lineTo(12f, 5.5f); moveTo(12f, 8.5f); lineTo(8.5f, 8.5f); lineTo(8.5f, 12f); moveTo(5.5f, 12f); lineTo(5.5f, 8.5f); lineTo(2f, 8.5f) } }
    val Play: ImageVector by lazy { buildFilled("ShuttlPlay") { moveTo(5f, 3f); lineTo(15f, 9f); lineTo(5f, 15f); close() } }
    val Pause: ImageVector by lazy {
        buildFilled("ShuttlPause") {
            moveTo(4f, 3f); lineTo(7.5f, 3f); lineTo(7.5f, 15f); lineTo(4f, 15f); close()
            moveTo(10.5f, 3f); lineTo(14f, 3f); lineTo(14f, 15f); lineTo(10.5f, 15f); close()
        }
    }
}

/** A 14-unit outlined glyph in the mock's 1.4 stroke. The colour is a placeholder for Icon's tint. */
private fun buildStroked(name: String, pathData: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 14.dp, defaultHeight = 14.dp, viewportWidth = 14f, viewportHeight = 14f)
        .apply {
            path(
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.4f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = pathData,
            )
        }
        .build()

/** An 18-unit filled glyph. */
private fun buildFilled(name: String, pathData: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 18.dp, defaultHeight = 18.dp, viewportWidth = 18f, viewportHeight = 18f)
        .apply { path(fill = SolidColor(Color.Black), pathBuilder = pathData) }
        .build()

private fun buildSun(): ImageVector = ImageVector.Builder(
    name = "ShuttlSun",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        fill = SolidColor(Color.Transparent),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2.5f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        // <circle cx="12" cy="12" r="5" />
        moveTo(7f, 12f)
        arcTo(
            horizontalEllipseRadius = 5f,
            verticalEllipseRadius = 5f,
            theta = 0f,
            isMoreThanHalf = false,
            isPositiveArc = true,
            x1 = 17f,
            y1 = 12f,
        )
        arcTo(
            horizontalEllipseRadius = 5f,
            verticalEllipseRadius = 5f,
            theta = 0f,
            isMoreThanHalf = false,
            isPositiveArc = true,
            x1 = 7f,
            y1 = 12f,
        )
        close()
        // 8 rays
        moveTo(12f, 1f); lineTo(12f, 3f)
        moveTo(12f, 21f); lineTo(12f, 23f)
        moveTo(4.22f, 4.22f); lineTo(5.64f, 5.64f)
        moveTo(18.36f, 18.36f); lineTo(19.78f, 19.78f)
        moveTo(1f, 12f); lineTo(3f, 12f)
        moveTo(21f, 12f); lineTo(23f, 12f)
        moveTo(4.22f, 19.78f); lineTo(5.64f, 18.36f)
        moveTo(18.36f, 5.64f); lineTo(19.78f, 4.22f)
    }
}.build()

private fun buildMoon(): ImageVector = ImageVector.Builder(
    name = "ShuttlMoon",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        fill = SolidColor(Color.Transparent),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2.5f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        // <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
        moveTo(21f, 12.79f)
        arcTo(
            horizontalEllipseRadius = 9f,
            verticalEllipseRadius = 9f,
            theta = 0f,
            isMoreThanHalf = true,
            isPositiveArc = true,
            x1 = 11.21f,
            y1 = 3f,
        )
        arcTo(
            horizontalEllipseRadius = 7f,
            verticalEllipseRadius = 7f,
            theta = 0f,
            isMoreThanHalf = false,
            isPositiveArc = false,
            x1 = 21f,
            y1 = 12.79f,
        )
        close()
    }
}.build()

private fun buildInfo(): ImageVector = ImageVector.Builder(
    name = "ShuttlInfo",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        fill = SolidColor(Color.Transparent),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2.5f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        // <circle cx="12" cy="12" r="10" />
        moveTo(2f, 12f)
        arcTo(
            horizontalEllipseRadius = 10f,
            verticalEllipseRadius = 10f,
            theta = 0f,
            isMoreThanHalf = false,
            isPositiveArc = true,
            x1 = 22f,
            y1 = 12f,
        )
        arcTo(
            horizontalEllipseRadius = 10f,
            verticalEllipseRadius = 10f,
            theta = 0f,
            isMoreThanHalf = false,
            isPositiveArc = true,
            x1 = 2f,
            y1 = 12f,
        )
        close()
        // <line x1="12" y1="8" x2="12" y2="12" />
        moveTo(12f, 8f); lineTo(12f, 12f)
        // <line x1="12" y1="16" x2="12.01" y2="16" />
        moveTo(12f, 16f); lineTo(12.01f, 16f)
    }
}.build()

/** A 24-unit outlined glyph in Lucide's 2 stroke. The colour is a placeholder for Icon's tint. */
private fun buildLucide(name: String, pathData: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .apply {
            path(
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = pathData,
            )
        }
        .build()

private fun androidx.compose.ui.graphics.vector.PathBuilder.trophy() {
    // <path d="M6 9H4.5a2.5 2.5 0 0 1 0-5H6"/>
    moveTo(6f, 9f); lineTo(4.5f, 9f)
    arcToRelative(2.5f, 2.5f, 0f, false, true, 0f, -5f)
    lineTo(6f, 4f)
    // <path d="M18 9h1.5a2.5 2.5 0 0 0 0-5H18"/>
    moveTo(18f, 9f); lineTo(19.5f, 9f)
    arcToRelative(2.5f, 2.5f, 0f, false, false, 0f, -5f)
    lineTo(18f, 4f)
    // <path d="M4 22h16"/>
    moveTo(4f, 22f); lineTo(20f, 22f)
    // <path d="M10 14.66V17c0 .55-.47.98-.97 1.21C7.85 18.75 7 20.24 7 22"/>
    moveTo(10f, 14.66f); lineTo(10f, 17f)
    curveToRelative(0f, 0.55f, -0.47f, 0.98f, -0.97f, 1.21f)
    curveTo(7.85f, 18.75f, 7f, 20.24f, 7f, 22f)
    // <path d="M14 14.66V17c0 .55.47.98.97 1.21C16.15 18.75 17 20.24 17 22"/>
    moveTo(14f, 14.66f); lineTo(14f, 17f)
    curveToRelative(0f, 0.55f, 0.47f, 0.98f, 0.97f, 1.21f)
    curveTo(16.15f, 18.75f, 17f, 20.24f, 17f, 22f)
    // <path d="M18 2H6v7a6 6 0 0 0 12 0V2Z"/>
    moveTo(18f, 2f); lineTo(6f, 2f); lineTo(6f, 9f)
    arcToRelative(6f, 6f, 0f, false, false, 12f, 0f)
    lineTo(18f, 2f)
    close()
}

private fun androidx.compose.ui.graphics.vector.PathBuilder.chartColumn() {
    // <path d="M3 3v16a2 2 0 0 0 2 2h16"/>
    moveTo(3f, 3f); lineTo(3f, 19f)
    arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
    lineTo(21f, 21f)
    // <path d="M18 17V9"/><path d="M13 17V5"/><path d="M8 17v-3"/>
    moveTo(18f, 17f); lineTo(18f, 9f)
    moveTo(13f, 17f); lineTo(13f, 5f)
    moveTo(8f, 17f); lineTo(8f, 14f)
}

private fun androidx.compose.ui.graphics.vector.PathBuilder.video() {
    // <path d="m16 13 5.223 3.482a.5.5 0 0 0 .777-.416V7.87a.5.5 0 0 0-.752-.432L16 10.5"/>
    moveTo(16f, 13f); lineToRelative(5.223f, 3.482f)
    arcToRelative(0.5f, 0.5f, 0f, false, false, 0.777f, -0.416f)
    lineTo(22f, 7.87f)
    arcToRelative(0.5f, 0.5f, 0f, false, false, -0.752f, -0.432f)
    lineTo(16f, 10.5f)
    // <rect x="2" y="6" width="14" height="12" rx="2"/>
    moveTo(4f, 6f); lineTo(14f, 6f)
    arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
    lineTo(16f, 16f)
    arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
    lineTo(4f, 18f)
    arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
    lineTo(2f, 8f)
    arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
    close()
}

private fun androidx.compose.ui.graphics.vector.PathBuilder.listOrdered() {
    // <path d="M10 12h11"/><path d="M10 18h11"/><path d="M10 6h11"/>
    moveTo(10f, 12f); lineTo(21f, 12f)
    moveTo(10f, 18f); lineTo(21f, 18f)
    moveTo(10f, 6f); lineTo(21f, 6f)
    // <path d="M4 10h2"/><path d="M4 6h1v4"/>
    moveTo(4f, 10f); lineTo(6f, 10f)
    moveTo(4f, 6f); lineTo(5f, 6f); lineTo(5f, 10f)
    // <path d="M6 18H4c0-1 2-2 2-3s-1-1.5-2-1"/>
    moveTo(6f, 18f); lineTo(4f, 18f)
    curveToRelative(0f, -1f, 2f, -2f, 2f, -3f)
    reflectiveCurveToRelative(-1f, -1.5f, -2f, -1f)
}

private fun androidx.compose.ui.graphics.vector.PathBuilder.tag() {
    // <path d="M12.586 2.586A2 2 0 0 0 11.172 2H4a2 2 0 0 0-2 2v7.172a2 2 0 0 0 .586 1.414
    //          l8.704 8.704a2.426 2.426 0 0 0 3.42 0l6.58-6.58a2.426 2.426 0 0 0 0-3.42z"/>
    moveTo(12.586f, 2.586f)
    arcTo(2f, 2f, 0f, false, false, 11.172f, 2f)
    lineTo(4f, 2f)
    arcToRelative(2f, 2f, 0f, false, false, -2f, 2f)
    lineTo(2f, 11.172f)
    arcToRelative(2f, 2f, 0f, false, false, 0.586f, 1.414f)
    lineToRelative(8.704f, 8.704f)
    arcToRelative(2.426f, 2.426f, 0f, false, false, 3.42f, 0f)
    lineToRelative(6.58f, -6.58f)
    arcToRelative(2.426f, 2.426f, 0f, false, false, 0f, -3.42f)
    close()
    // <circle cx="7.5" cy="7.5" r=".5"/>: with the 2-unit stroke this is the
    // tag's eyelet dot.
    moveTo(7f, 7.5f)
    arcTo(0.5f, 0.5f, 0f, true, true, 8f, 7.5f)
    arcTo(0.5f, 0.5f, 0f, true, true, 7f, 7.5f)
    close()
}

package com.badmintontracker.android.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Lucide-style outlined icons inlined as [ImageVector]s so the app doesn't
 * pull in `androidx.compose.material:material-icons-extended` (~2-3 MB APK
 * cost). The sun, moon and info glyphs are taken verbatim from the web app
 * reference `badminton-tracker/src/views/LoginView.vue`; the match list's six
 * glyphs come from lucide-static 0.460.0, not the web app, which has none of
 * them.
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
    // The scoring board's Undo. Lucide's undo-2 at its stock stroke, drawn at
    // 18dp beside the label like ShuttlButton's leading icon.
    val Undo: ImageVector by lazy { buildLucide("ShuttlUndo") { undo() } }

    // The match list's status line: where a match's video is, then what
    // analysis it has. Lucide 0.460.0's own path strings (rects, lines and
    // circles rewritten as paths), parsed by Compose rather than hand-traced:
    // the flame and the cloud are arcs a transcription would get subtly wrong.
    /** On this phone. */
    val Smartphone: ImageVector by lazy {
        buildLucideSvg("ShuttlSmartphone", "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M12 18h.01")
    }
    /** In the cloud. */
    val Cloud: ImageVector by lazy {
        buildLucideSvg("ShuttlCloud", "M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z")
    }
    /** Rallies. */
    val Film: ImageVector by lazy {
        buildLucideSvg(
            "ShuttlFilm",
            "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
            "M7 3v18", "M3 7.5h4", "M3 12h18", "M3 16.5h4", "M17 3v18", "M17 7.5h4", "M17 16.5h4",
        )
    }
    /** Heatmap. */
    val Flame: ImageVector by lazy {
        buildLucideSvg(
            "ShuttlFlame",
            "M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3-1.072-2.143-.224-4.054 2-6 .5 2.5 2 4.9 4 6.5 " +
                "2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.153.433-2.294 1-3a2.5 2.5 0 0 0 2.5 2.5z",
        )
    }
    /** Skeleton. */
    val PersonStanding: ImageVector by lazy {
        buildLucideSvg("ShuttlPersonStanding", "M13 5a1 1 0 1 1-2 0a1 1 0 1 1 2 0z", "m9 20 3-6 3 6", "m6 8 6 2 6-2", "M12 10v4")
    }
    /**
     * The match list's legend button: Lucide's info, a circle with an "i". Not
     * [Info], which despite its name is the alert "!" ErrorBanner draws.
     */
    val InfoCircle: ImageVector by lazy {
        buildLucideSvg("ShuttlInfoCircle", "M22 12a10 10 0 1 1-20 0a10 10 0 1 1 20 0z", "M12 16v-4", "M12 8h.01")
    }
    /** Score. */
    val Hash: ImageVector by lazy {
        buildLucideSvg("ShuttlHash", "M4 9h16", "M4 15h16", "M10 3 8 21", "M16 3l-2 18")
    }

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
    /**
     * The settings gear on the video card, where the speed chip used to be. Drawn
     * rather than imported for the reason at the top of this file, and computed
     * rather than traced: eight teeth are sixteen flanks, and a loop cannot get one
     * of them wrong. Same 14-unit, 1.4 stroke family as the chips beside it.
     */
    val Settings: ImageVector by lazy {
        buildStroked("ShuttlSettings") {
            val centre = 7f
            val tip = 5.9f
            val root = 4.4f
            fun at(radius: Float, degrees: Float): Pair<Float, Float> {
                val radians = Math.toRadians(degrees.toDouble())
                return centre + radius * kotlin.math.cos(radians).toFloat() to centre + radius * kotlin.math.sin(radians).toFloat()
            }
            for (tooth in 0 until 8) {
                val angle = tooth * 45f
                val points = listOf(at(root, angle - 14f), at(tip, angle - 8f), at(tip, angle + 8f), at(root, angle + 14f))
                points.forEachIndexed { index, (x, y) ->
                    if (tooth == 0 && index == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            close()
            // The hub, as two half turns: one arc cannot return to its own start.
            moveTo(centre + 1.9f, centre)
            arcTo(1.9f, 1.9f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = centre - 1.9f, y1 = centre)
            arcTo(1.9f, 1.9f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = centre + 1.9f, y1 = centre)
        }
    }
    /** The floating label window's title bar: a six-dot grip says "drag me", and a pair of chevrons folds it. */
    val Grip: ImageVector by lazy { buildStroked("ShuttlGrip") { for (x in listOf(5f, 9f)) for (y in listOf(3.5f, 7f, 10.5f)) { moveTo(x, y); lineTo(x + 0.01f, y) } } }
    val ChevronUp: ImageVector by lazy { buildStroked("ShuttlChevronUp") { moveTo(3f, 9f); lineTo(7f, 5f); lineTo(11f, 9f) } }
    val ChevronDown: ImageVector by lazy { buildStroked("ShuttlChevronDown") { moveTo(3f, 5f); lineTo(7f, 9f); lineTo(11f, 5f) } }
    /** Two opposed arrows: the scoring board's "hand the serve across" mark. Same 14-unit, 1.4 stroke family. */
    val Swap: ImageVector by lazy { buildStroked("ShuttlSwap") { moveTo(2f, 4.5f); lineTo(12f, 4.5f); moveTo(9.5f, 2f); lineTo(12f, 4.5f); lineTo(9.5f, 7f); moveTo(12f, 9.5f); lineTo(2f, 9.5f); moveTo(4.5f, 7f); lineTo(2f, 9.5f); lineTo(4.5f, 12f) } }
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

/**
 * [buildLucide] from Lucide's own path strings, one per SVG element. Compose's
 * parser handles the arcs and the implicit linetos after a moveto that some of
 * these rely on (see LucideGlyphsTest).
 */
private fun buildLucideSvg(name: String, vararg d: String): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .apply {
            d.forEach { pathString ->
                addPath(
                    pathData = addPathNodes(pathString),
                    fill = SolidColor(Color.Transparent),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
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

private fun androidx.compose.ui.graphics.vector.PathBuilder.undo() {
    // <path d="M9 14 4 9l5-5"/>
    moveTo(9f, 14f); lineTo(4f, 9f); lineTo(9f, 4f)
    // <path d="M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5a5.5 5.5 0 0 1-5.5 5.5H11"/>
    moveTo(4f, 9f); lineTo(14.5f, 9f)
    arcToRelative(5.5f, 5.5f, 0f, false, true, 5.5f, 5.5f)
    arcToRelative(5.5f, 5.5f, 0f, false, true, -5.5f, 5.5f)
    lineTo(11f, 20f)
}

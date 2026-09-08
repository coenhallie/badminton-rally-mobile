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

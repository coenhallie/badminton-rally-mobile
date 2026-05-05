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
 * All paths use stroke = SolidColor(Color.Unspecified) so that the
 * `tint` parameter on [androidx.compose.material3.Icon] applies.
 */
object ShuttlIcons {
    val Sun: ImageVector by lazy { buildSun() }
    val Moon: ImageVector by lazy { buildMoon() }
    val Info: ImageVector by lazy { buildInfo() }
}

private fun buildSun(): ImageVector = ImageVector.Builder(
    name = "ShuttlSun",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        fill = SolidColor(Color.Transparent),
        stroke = SolidColor(Color.Unspecified),
        strokeLineWidth = 2f,
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
        stroke = SolidColor(Color.Unspecified),
        strokeLineWidth = 2f,
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
        stroke = SolidColor(Color.Unspecified),
        strokeLineWidth = 2f,
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

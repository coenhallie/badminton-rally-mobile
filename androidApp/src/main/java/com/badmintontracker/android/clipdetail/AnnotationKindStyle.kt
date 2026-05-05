package com.badmintontracker.android.clipdetail

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.badmintontracker.shared.model.AnnotationKind

internal data class AnnotationKindStyle(
    val label: String,
    val container: Color,
    val onContainer: Color,
)

@Composable
internal fun AnnotationKind.style(): AnnotationKindStyle = when (this) {
    AnnotationKind.GOOD_SHOT      -> AnnotationKindStyle(
        label = "Good shot",
        container = Color(0xFF2E7D32),
        onContainer = Color.White,
    )
    AnnotationKind.FORCED_ERROR   -> AnnotationKindStyle(
        label = "Forced error",
        container = Color(0xFFB26A00),
        onContainer = Color.Black,
    )
    AnnotationKind.UNFORCED_ERROR -> AnnotationKindStyle(
        label = "Unforced error",
        container = Color(0xFFC62828),
        onContainer = Color.White,
    )
}

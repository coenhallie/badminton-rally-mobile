package com.badmintontracker.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.badmintontracker.android.R

/**
 * The type scale as raw numbers.
 *
 * Split out from the TextStyles so it can be asserted from a unit test with no
 * font resource and no Android runtime. Mirrors iosApp's ShuttlType.Role number
 * for number.
 *
 * Tracking is stored in em, the unit the design is expressed in, and converted
 * on the way out; storing sp would mean re-deriving it by hand on every size
 * change.
 */
internal object ShuttlScale {
    data class Role(
        val sizeSp: Float,
        val weight: FontWeight,
        val trackingEm: Float,
        val lineHeightMultiple: Float,
    ) {
        val trackingSp: Float get() = sizeSp * trackingEm
    }

    val display        = Role(40f, FontWeight.Medium, -0.035f, 1.08f)
    val headlineLarge  = Role(28f, FontWeight.Medium, -0.030f, 1.15f)
    val headlineMedium = Role(22f, FontWeight.Medium, -0.020f, 1.20f)
    val statNumber     = Role(26f, FontWeight.Medium, -0.030f, 1.15f)
    val titleLarge     = Role(16f, FontWeight.SemiBold, -0.010f, 1.30f)
    val titleMedium    = Role(15f, FontWeight.SemiBold, -0.010f, 1.30f)
    val bodyLarge      = Role(16f, FontWeight.Normal, 0f, 1.45f)
    val bodyMedium     = Role(14f, FontWeight.Normal, 0f, 1.45f)
    val bodySmall      = Role(12f, FontWeight.Normal, 0f, 1.40f)
    val labelSmall     = Role(11f, FontWeight.Medium, 0.050f, 1.30f)
}

internal val Archivo = FontFamily(
    Font(R.font.archivo_regular, FontWeight.Normal),
    Font(R.font.archivo_medium, FontWeight.Medium),
    Font(R.font.archivo_semibold, FontWeight.SemiBold),
    Font(R.font.archivo_bold, FontWeight.Bold),
)

private fun ShuttlScale.Role.toTextStyle() = TextStyle(
    fontFamily    = Archivo,
    fontWeight    = weight,
    fontSize      = sizeSp.sp,
    lineHeight    = (sizeSp * lineHeightMultiple).sp,
    letterSpacing = trackingSp.sp,
)

internal val ShuttlTypography = Typography(
    headlineLarge  = ShuttlScale.headlineLarge.toTextStyle(),
    headlineMedium = ShuttlScale.headlineMedium.toTextStyle(),
    titleLarge     = ShuttlScale.titleLarge.toTextStyle(),
    titleMedium    = ShuttlScale.titleMedium.toTextStyle(),
    bodyLarge      = ShuttlScale.bodyLarge.toTextStyle(),
    bodyMedium     = ShuttlScale.bodyMedium.toTextStyle(),
    bodySmall      = ShuttlScale.bodySmall.toTextStyle(),
    // Tiny uppercase tracked label. The uppercasing is the caller's job, the
    // tracking is this style's.
    labelSmall     = ShuttlScale.labelSmall.toTextStyle(),
)

/**
 * The two roles M3's [Typography] has no slot for. Phase 2's hero uses
 * [display]; phase 3's stat tiles use [statNumber].
 */
internal object ShuttlTypeExtras {
    val display    = ShuttlScale.display.toTextStyle()
    val statNumber = ShuttlScale.statNumber.toTextStyle()
}

package com.badmintontracker.analysis.court

import com.badmintontracker.analysis.camera.GreyImage

/**
 * A decoded colour frame, row-major, one packed ARGB int per pixel (as from
 * `Bitmap.getPixels` on Android or a `CGImage` bitmap context on iOS).
 *
 * Public because it is the boundary type: platform code builds one from
 * whatever it decoded, then [luma] hands the rest of `:analysis` a plain
 * [GreyImage] to work from.
 */
class RgbImage(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width >= 1 && height >= 1) { "empty image $width x $height" }
        require(argb.size == width * height) { "${argb.size} pixels for $width x $height" }
    }
}

/**
 * BT.601 luma, the same weights `court_probe.py` gets from
 * `cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)`.
 */
internal fun RgbImage.luma(): GreyImage {
    val out = FloatArray(width * height)
    for (i in out.indices) {
        val p = argb[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        out[i] = 0.299f * r + 0.587f * g + 0.114f * b
    }
    return GreyImage(width, height, out)
}

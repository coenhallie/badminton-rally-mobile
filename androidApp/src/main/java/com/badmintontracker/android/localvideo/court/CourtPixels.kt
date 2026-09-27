package com.badmintontracker.android.localvideo.court

import android.graphics.Bitmap
import android.util.Log
import com.badmintontracker.analysis.court.CourtFinder
import com.badmintontracker.analysis.court.CourtFinding
import com.badmintontracker.analysis.court.RgbImage
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.shared.localvideo.court.CourtPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The frame's pixels as the court finder reads them: one ARGB int per pixel,
 * row by row, at the bitmap's own size, which is the source resolution the
 * marks are in.
 *
 * A HARDWARE bitmap keeps its pixels on the GPU and throws from `getPixels`,
 * so it is copied to memory first. `MediaMetadataRetriever` hands back
 * ARGB_8888 today; the copy is for a decoder that one day does not.
 */
fun Bitmap.toRgbImage(): RgbImage {
    val readable = if (config == Bitmap.Config.HARDWARE) {
        copy(Bitmap.Config.ARGB_8888, false) ?: error("Couldn't copy a hardware bitmap")
    } else {
        this
    }
    val argb = IntArray(readable.width * readable.height)
    readable.getPixels(argb, 0, readable.width, 0, 0, readable.width, readable.height)
    if (readable !== this) readable.recycle()
    return RgbImage(readable.width, readable.height, argb)
}

/**
 * The court finder as the marking screen calls it: [find] looks for the court
 * on the frame, [fromCorners] completes it from the four corners the coach
 * tapped (TL, TR, BR, BL in source pixels). A seam so the view model's tests
 * can answer in any order, or throw, without decoding a frame.
 */
class CourtFinderPort(
    val find: suspend (CourtFrame) -> CourtFinding,
    val fromCorners: suspend (CourtFrame, List<CourtPoint>) -> CourtFinding,
)

/**
 * [CourtFinder] on the frame's bitmap, off the main thread: a search takes a
 * fraction of a second on a desktop and longer on a phone, and the pixel copy
 * is a full frame's worth of ints.
 *
 * A frame without a bitmap (the unit tests' frames) is simply not found.
 */
val DefaultCourtFinderPort = CourtFinderPort(
    find = { frame -> onPixels(frame) { CourtFinder.find(it) } },
    fromCorners = { frame, corners ->
        onPixels(frame) { image ->
            CourtFinder.fromCorners(image, corners.map { Point(it.x.toDouble(), it.y.toDouble()) })
        }
    },
)

private suspend fun onPixels(frame: CourtFrame, run: (RgbImage) -> CourtFinding): CourtFinding {
    val bitmap = frame.frame ?: return CourtFinding(null, null, "no frame", null, 0)
    return withContext(Dispatchers.Default) { run(bitmap.toRgbImage()) }
}

/**
 * Where the view model reports what the finder said, for tuning it on real
 * videos: logcat in the app. Injected because `android.util.Log` is a stub
 * that throws on the JVM the unit tests run on.
 */
interface CourtFinderLog {
    /** The finder answered [call] with [finding], found or not. */
    fun answered(call: String, finding: CourtFinding)

    /** [call] threw; the screen carries on as if nothing was found. */
    fun failed(call: String, error: Throwable)
}

object LogcatCourtFinderLog : CourtFinderLog {
    private const val TAG = "CourtFinder"

    override fun answered(call: String, finding: CourtFinding) {
        Log.i(TAG, "$call: reason=${finding.reason} residualPx=${finding.residualPx} millis=${finding.millis}")
    }

    override fun failed(call: String, error: Throwable) {
        Log.w(TAG, "$call threw; falling back to manual marking", error)
    }
}

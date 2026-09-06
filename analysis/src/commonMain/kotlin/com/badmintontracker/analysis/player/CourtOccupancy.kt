package com.badmintontracker.analysis.player

import com.badmintontracker.analysis.geometry.Court
import com.badmintontracker.analysis.geometry.Point
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where the player spent their time, in court metres.
 *
 * Accumulates **seconds**, not sample counts. Occupancy is a question about
 * time, so a map built from counts changes meaning whenever the sample spacing
 * changes: dropped frames, a different frame rate, or filtering to rallies would
 * each silently reweight it. Carrying each sample's own `dt` makes the map
 * invariant to all three, and is what lets whole-match and rallies-only be two
 * views of one computation.
 *
 * The grid is in metres rather than video pixels, which is not only about being
 * comparable between matches. Under perspective a pixel near the camera covers
 * far less court than a pixel at the far baseline, so the fixed pixel-space
 * blur the web app applies is a spatially varying blur in the real world: it
 * over-smooths the far court and under-smooths the near. On a metre grid the
 * kernel is the same physical size everywhere.
 */
class CourtOccupancy(
    val cellSizeM: Double = DEFAULT_CELL_SIZE_M,
    val marginM: Double = NearPlayerSelector.OUT_OF_COURT_MARGIN_M,
) {
    val columns: Int = ((Court.WIDTH_DOUBLES + 2 * marginM) / cellSizeM).roundToInt()
    val rows: Int = ((Court.LENGTH + 2 * marginM) / cellSizeM).roundToInt()

    private val cells = DoubleArray(columns * rows)
    private var total = 0.0

    /** Seconds accumulated, before any smoothing. */
    val totalSeconds: Double get() = total

    fun add(position: Point, seconds: Double) {
        if (seconds <= 0.0) return
        val col = ((position.x + marginM) / cellSizeM).toInt()
        val row = ((position.y + marginM) / cellSizeM).toInt()
        if (col !in 0 until columns || row !in 0 until rows) return
        cells[row * columns + col] += seconds
        total += seconds
    }

    fun addAll(samples: List<PlayerSample>, fps: Double) {
        if (fps <= 0.0) return
        // Each sample covers the gap to the next one, so a track with holes in
        // it does not credit the frames it never saw. The last sample gets one
        // frame, which is all that is known about it.
        samples.forEachIndexed { i, sample ->
            val next = samples.getOrNull(i + 1)
            val frames = if (next == null) 1 else max(1, next.frame - sample.frame)
            add(sample.courtPosition, frames / fps)
        }
    }

    /** Raw seconds per cell, row-major from the far baseline. */
    fun grid(): List<List<Double>> =
        (0 until rows).map { r -> (0 until columns).map { c -> cells[r * columns + c] } }

    /**
     * Smoothed with a Gaussian whose width is given in metres.
     *
     * Separable, so the cost is linear in the radius rather than quadratic. The
     * radius is where the kernel has fallen far enough to stop mattering, at
     * three sigma.
     */
    fun smoothed(sigmaM: Double = DEFAULT_SIGMA_M): List<List<Double>> {
        if (sigmaM <= 0.0) return grid()
        val sigma = sigmaM / cellSizeM
        val radius = max(1, (3 * sigma).roundToInt())
        val kernel = DoubleArray(2 * radius + 1) { i ->
            val d = (i - radius).toDouble()
            exp(-(d * d) / (2 * sigma * sigma))
        }
        val sum = kernel.sum()
        for (i in kernel.indices) kernel[i] /= sum

        val horizontal = DoubleArray(cells.size)
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                var acc = 0.0
                for (k in kernel.indices) {
                    val cc = min(columns - 1, max(0, c + k - radius))
                    acc += cells[r * columns + cc] * kernel[k]
                }
                horizontal[r * columns + c] = acc
            }
        }
        val out = DoubleArray(cells.size)
        for (r in 0 until rows) {
            for (c in 0 until columns) {
                var acc = 0.0
                for (k in kernel.indices) {
                    val rr = min(rows - 1, max(0, r + k - radius))
                    acc += horizontal[rr * columns + c] * kernel[k]
                }
                out[r * columns + c] = acc
            }
        }
        return (0 until rows).map { r -> (0 until columns).map { c -> out[r * columns + c] } }
    }

    companion object {
        /**
         * Finer than the measured position error, so the grid is not what limits
         * the map: nano at 960 disagrees with the largest model by about 9cm.
         */
        const val DEFAULT_CELL_SIZE_M = 0.25

        /** Roughly a stride, which is the scale a coach reads a heatmap at. */
        const val DEFAULT_SIGMA_M = 0.5
    }
}

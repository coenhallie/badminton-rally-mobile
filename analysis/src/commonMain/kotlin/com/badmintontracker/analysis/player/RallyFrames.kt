package com.badmintontracker.analysis.player

/**
 * The frames that fall inside any rally: what "during play" means for a
 * heatmap drawn from the rallies alone.
 *
 * Built from the clip windows as they are stored, padding included. The
 * detector's own bounds run from the return of serve to the last contact, so
 * the clip's two seconds of pre-roll hold the serve and its one and a half of
 * post-roll the shuttle landing: trimming them would cut play, not the walk
 * between points.
 *
 * Two rallies closer together than that padding give clips that overlap, so
 * the windows are merged: a frame in two clips is one frame of play.
 */
class RallyFrames private constructor(private val ranges: List<IntRange>) {

    /** Frames inside any rally, each counted once. */
    val frameCount: Int = ranges.sumOf { it.last - it.first + 1 }

    val isEmpty: Boolean get() = ranges.isEmpty()

    /** The last frame of the rally [frame] falls in, or null between rallies. */
    fun endOf(frame: Int): Int? {
        var lo = 0
        var hi = ranges.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val range = ranges[mid]
            when {
                frame < range.first -> hi = mid - 1
                frame > range.last -> lo = mid + 1
                else -> return range.last
            }
        }
        return null
    }

    /**
     * The share of rally frames the player was found in: the heatmap's quality
     * figure, over play alone. Every frame counts in the denominator, the way
     * [RallyBase.coverage] counts a rally's.
     */
    fun coverage(track: PlayerTrack): Double =
        if (frameCount == 0) 0.0 else track.samples.count { endOf(it.frame) != null }.toDouble() / frameCount

    companion object {
        fun of(windows: List<RallyWindow>, fps: Double): RallyFrames {
            if (fps <= 0.0) return RallyFrames(emptyList())
            val merged = ArrayList<IntRange>()
            for (range in windows.filter { it.isBounded }.map { it.frames(fps) }.sortedBy { it.first }) {
                val last = merged.lastOrNull()
                if (last != null && range.first <= last.last + 1) {
                    merged[merged.size - 1] = last.first..maxOf(last.last, range.last)
                } else {
                    merged += range
                }
            }
            return RallyFrames(merged)
        }
    }
}

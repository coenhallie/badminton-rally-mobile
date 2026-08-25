package com.badmintontracker.shared.playback

/**
 * Where a "skip back / skip forward" button should seek to.
 *
 * Shared rather than ported per platform (the way FrameStepMath is) because it
 * needs nothing from the native player beyond two numbers: frame stepping has
 * to ask the decoder for the frame rate, a timed skip does not.
 */
object SkipMath {

    /**
     * @param positionMs current playhead; a negative reading counts as the start.
     * @param deltaSeconds signed skip interval.
     * @param durationMs total length, or any non-positive value when the player
     *   does not know it yet (ExoPlayer reports C.TIME_UNSET, AVPlayer an
     *   indefinite CMTime) - in that case the forward skip is left unclamped.
     */
    fun targetMs(positionMs: Long, deltaSeconds: Int, durationMs: Long): Long {
        val from = positionMs.coerceAtLeast(0L)
        val target = from + deltaSeconds * 1000L
        val end = if (durationMs > 0L) durationMs else Long.MAX_VALUE
        return target.coerceIn(0L, end)
    }
}

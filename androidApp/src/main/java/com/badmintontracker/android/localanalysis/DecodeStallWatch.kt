package com.badmintontracker.android.localanalysis

/**
 * Whether the decoder in [VideoFrameSource.forEachFrame] has stopped
 * answering: it holds input and has returned no frame for [limitMs] of wall
 * time.
 *
 * Without this a decoder that accepts samples and never returns a frame hangs
 * the analysis forever with no error, which is what the emulator's
 * `c2.goldfish.hevc.decoder` does with `IMG_5892` (fifteen samples in, no
 * frame in 30 s, and a run that sat idle for over ten minutes). A phone is far
 * less likely to hit it, but a run that fails with a reason beats one that
 * never ends.
 *
 * The wait starts at the first sample queued while the decoder owes nothing,
 * and restarts each time the loop is done with a returned buffer. Further
 * input does not restart it (feeding a decoder that is not answering is not
 * progress), and the end-of-stream buffer is input like any other, so a
 * decoder that never delivers its last frames or its end of stream stalls the
 * same way. The time the loop spends on a frame, pose inference included,
 * falls before [outputReturned] and so is never counted against the decoder.
 *
 * Pure and clock-driven so the decision is testable on the JVM; the loop owns
 * the codec and the throw.
 *
 * @param clockMs a monotonic clock in milliseconds.
 */
internal class DecodeStallWatch(
    private val clockMs: () -> Long,
    private val limitMs: Long = LIMIT_MS,
) {
    /** When the decoder started owing a buffer, or null while it owes none. */
    private var waitingSinceMs: Long? = null

    /** A sample, or the end-of-stream buffer, went into the decoder. */
    fun inputQueued() {
        if (waitingSinceMs == null) waitingSinceMs = clockMs()
    }

    /**
     * The loop is done with a buffer the decoder returned. The decoder still
     * owes the rest of the stream, down to its end-of-stream buffer, so the
     * wait restarts rather than ends.
     */
    fun outputReturned() {
        waitingSinceMs = clockMs()
    }

    /** Whether the decoder has owed a buffer for [limitMs] or longer. */
    fun stalled(): Boolean {
        val since = waitingSinceMs ?: return false
        return clockMs() - since >= limitMs
    }

    companion object {
        /**
         * Fifteen seconds: far past any pause a working decoder takes between
         * frames, and the goldfish stall is forever. A pathologically slow
         * decoder failing here is the cost the ruling accepted.
         */
        const val LIMIT_MS = 15_000L

        /** What a stalled run fails with, which the analysis state shows as is. */
        fun message(framesSoFar: Int): String =
            "The video's decoder stopped responding: no frame came back for ${LIMIT_MS / 1000} s " +
                "after frame $framesSoFar."
    }
}

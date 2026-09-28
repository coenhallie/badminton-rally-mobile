package com.badmintontracker.android.localanalysis

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The decode loop's stall decision on a clock the test moves by hand. The
 * loop itself needs a real MediaCodec; what matters here is which silences
 * count, and that is all in [DecodeStallWatch].
 */
class DecodeStallWatchTest {

    private var now = 1_000_000L
    private val watch = DecodeStallWatch(clockMs = { now })

    @Test
    fun nothing_queued_is_never_a_stall() {
        // Before the first sample reaches the decoder it owes nothing.
        now += 60_000
        watch.stalled() shouldBe false
    }

    @Test
    fun a_decoder_that_takes_input_and_never_returns_a_frame_stalls_at_15_s() {
        // The emulator's goldfish HEVC decoder on IMG_5892: fifteen samples
        // in, no frame out, forever.
        repeat(15) { watch.inputQueued(); now += 100 }
        now = 1_000_000L + 14_999
        watch.stalled() shouldBe false
        now = 1_000_000L + 15_000
        watch.stalled() shouldBe true
    }

    @Test
    fun more_input_does_not_restart_the_wait() {
        // Feeding a decoder that is not answering is not progress.
        watch.inputQueued()
        repeat(20) { now += 1_000; watch.inputQueued() }
        watch.stalled() shouldBe true
    }

    @Test
    fun each_frame_restarts_the_wait() {
        watch.inputQueued()
        repeat(10) {
            now += 14_000
            watch.stalled() shouldBe false
            watch.outputReturned()
            watch.inputQueued()
        }
        now += 15_000
        watch.stalled() shouldBe true
    }

    @Test
    fun time_spent_on_a_frame_after_it_came_back_is_not_the_decoders() {
        // The loop hands each frame to pose inference before asking for the
        // next; a slow model is not a slow decoder, so the wait starts when
        // the loop reports the frame done, not when the frame arrived.
        watch.inputQueued()
        now += 1_000
        now += 40_000 // the body runs
        watch.outputReturned()
        now += 14_999
        watch.stalled() shouldBe false
    }

    @Test
    fun after_end_of_stream_the_decoder_still_owes_its_last_frames() {
        // Input end of stream queued, then silence: no frame and no output end
        // of stream for 15 s is a stall like any other.
        watch.inputQueued()
        watch.outputReturned()
        now += 500
        watch.inputQueued() // the end-of-stream buffer
        now += 15_000
        watch.stalled() shouldBe true
    }

    @Test
    fun the_message_says_the_decoder_stopped_responding() {
        DecodeStallWatch.message(framesSoFar = 12) shouldBe
            "The video's decoder stopped responding: no frame came back for 15 s after frame 12."
    }
}

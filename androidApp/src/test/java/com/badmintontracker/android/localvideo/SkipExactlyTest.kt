package com.badmintontracker.android.localvideo

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import kotlin.test.Test

/** Wraps a stream so skip() under-delivers (returns at most [maxPerCall] bytes). */
private fun reluctantStream(data: ByteArray, maxPerCall: Long = 1): InputStream =
    object : FilterInputStream(ByteArrayInputStream(data)) {
        override fun skip(n: Long): Long = super.skip(minOf(n, maxPerCall))
    }

class SkipExactlyTest {

    @Test
    fun skips_full_offset_even_when_skip_under_delivers() {
        val stream = reluctantStream(byteArrayOf(0, 1, 2, 3, 4, 5))

        stream.skipExactly(4)

        stream.read() shouldBe 4   // positioned exactly past the offset
    }

    @Test
    fun zero_offset_is_a_no_op() {
        val stream = ByteArrayInputStream(byteArrayOf(7, 8))

        stream.skipExactly(0)

        stream.read() shouldBe 7
    }

    @Test
    fun throws_when_stream_ends_before_offset() {
        val stream = reluctantStream(byteArrayOf(0, 1))

        shouldThrow<IllegalStateException> { stream.skipExactly(5) }
    }

    @Test
    fun skips_via_read_when_skip_returns_zero() {
        // Some content-provider streams return 0 from skip(); must fall back to reading.
        val stream = object : FilterInputStream(ByteArrayInputStream(byteArrayOf(0, 1, 2, 3))) {
            override fun skip(n: Long): Long = 0
        }

        stream.skipExactly(3)

        stream.read() shouldBe 3
    }
}

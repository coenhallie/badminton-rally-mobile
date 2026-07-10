package com.badmintontracker.android.localvideo

import java.io.InputStream

/**
 * Advance the stream exactly [count] bytes or throw. InputStream.skip() may
 * under-deliver (content-provider streams routinely return 0), and a resumed
 * TUS upload positioned at the wrong offset silently corrupts the video —
 * so fall back to reading and fail loudly if the stream ends early.
 */
fun InputStream.skipExactly(count: Long) {
    var remaining = count
    val buffer = lazy { ByteArray(DISCARD_BUFFER_SIZE) }
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
            continue
        }
        val read = read(buffer.value, 0, minOf(remaining, DISCARD_BUFFER_SIZE.toLong()).toInt())
        check(read >= 0) { "Video stream ended ${remaining} bytes before the resume offset ($count)" }
        remaining -= read
    }
}

private const val DISCARD_BUFFER_SIZE = 8 * 1024

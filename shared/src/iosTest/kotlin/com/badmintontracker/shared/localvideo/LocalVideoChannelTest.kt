package com.badmintontracker.shared.localvideo

import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.core.readBytes
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSFileManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalVideoChannelTest {

    private fun writeTempFile(bytes: ByteArray): String {
        val path = NSTemporaryDirectory() + "channel-test-${bytes.size}-${bytes.hashCode()}.bin"
        val ok = NSFileManager.defaultManager.createFileAtPath(
            path, contents = bytes.toNSData(), attributes = null
        )
        assertTrue(ok, "temp file created")
        return path
    }

    @Test
    fun streams_full_file_from_offset_zero() = runTest {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }   // > one chunk
        val path = writeTempFile(bytes)
        val channel = openLocalVideoChannel(path, offset = 0)
        val read = channel.readRemaining().readBytes()
        assertContentEquals(bytes, read)
    }

    @Test
    fun streams_from_resume_offset() = runTest {
        val bytes = ByteArray(70_000) { (it % 251).toByte() }
        val path = writeTempFile(bytes)
        val channel = openLocalVideoChannel(path, offset = 65_536)
        val read = channel.readRemaining().readBytes()
        assertContentEquals(bytes.copyOfRange(65_536, bytes.size), read)
    }

    @Test
    fun missing_file_throws_with_parity_message() {
        assertFailsWith<IllegalStateException> {
            openLocalVideoChannel(NSTemporaryDirectory() + "does-not-exist.bin", offset = 0)
        }.also {
            assertTrue(it.message!!.contains("missing or access was revoked"))
        }
    }
}

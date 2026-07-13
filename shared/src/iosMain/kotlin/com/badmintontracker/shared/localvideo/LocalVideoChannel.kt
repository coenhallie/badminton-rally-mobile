package com.badmintontracker.shared.localvideo

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileHandle
import platform.Foundation.create
import platform.Foundation.fileHandleForReadingAtPath
import platform.posix.memcpy

private const val CHUNK_BYTES = 65_536uL

/**
 * Streams a local video file into a ByteReadChannel starting at [offset] —
 * the iOS mirror of Android's contentResolver + skipExactly + toByteReadChannel.
 * Never loads the whole file into memory.
 */
@OptIn(ExperimentalForeignApi::class)
fun openLocalVideoChannel(absolutePath: String, offset: Long): ByteReadChannel {
    val handle = NSFileHandle.fileHandleForReadingAtPath(absolutePath)
        ?: error("Video file is missing or access was revoked")
    handle.seekOrThrow(offset.toULong())
    val channel = ByteChannel(autoFlush = true)
    CoroutineScope(Dispatchers.Default).launch {
        try {
            while (true) {
                val data = handle.readChunkOrThrow(CHUNK_BYTES)
                val bytes = data?.toByteArray() ?: ByteArray(0)
                if (bytes.isEmpty()) break
                channel.writeFully(bytes, 0, bytes.size)
            }
            channel.close(null)
        } catch (t: Throwable) {
            channel.close(t)
        } finally {
            handle.closeAndReturnError(error = null)
        }
    }
    return channel
}

@OptIn(ExperimentalForeignApi::class)
private fun NSFileHandle.seekOrThrow(offset: ULong) = memScoped {
    val err = alloc<ObjCObjectVar<NSError?>>()
    seekToOffset(offset, err.ptr)
    if (err.value != null) {
        error("Couldn't seek video file to resume offset: ${err.value?.localizedDescription}")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSFileHandle.readChunkOrThrow(length: ULong): NSData? = memScoped {
    val err = alloc<ObjCObjectVar<NSError?>>()
    val data = readDataUpToLength(length, err.ptr)
    if (data == null && err.value != null) {
        error("Couldn't read video file: ${err.value?.localizedDescription}")
    }
    data
}

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData = this.usePinned { pinned ->
    NSData.create(bytes = if (isEmpty()) null else pinned.addressOf(0), length = size.toULong())
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).apply {
        usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
}

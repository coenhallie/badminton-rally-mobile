package com.badmintontracker.shared.util

/** Tiny portable mutual-exclusion lock for repository read-modify-write cycles. */
internal expect class SyncLock() {
    fun lock()
    fun unlock()
}

internal inline fun <T> SyncLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

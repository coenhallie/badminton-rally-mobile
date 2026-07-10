package com.badmintontracker.shared.util

import java.util.concurrent.locks.ReentrantLock

internal actual class SyncLock {
    private val delegate = ReentrantLock()
    actual fun lock() = delegate.lock()
    actual fun unlock() = delegate.unlock()
}

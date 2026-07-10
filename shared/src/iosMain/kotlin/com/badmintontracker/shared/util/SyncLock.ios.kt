package com.badmintontracker.shared.util

import platform.Foundation.NSRecursiveLock

internal actual class SyncLock {
    private val delegate = NSRecursiveLock()
    actual fun lock() = delegate.lock()
    actual fun unlock() = delegate.unlock()
}

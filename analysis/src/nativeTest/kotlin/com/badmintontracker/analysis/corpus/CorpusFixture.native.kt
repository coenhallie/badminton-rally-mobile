package com.badmintontracker.analysis.corpus

/**
 * Native has no fixture files, so every corpus-backed test skips there.
 *
 * The corpus is read off the JVM test classpath, which the iOS test binary
 * has no equivalent of. That is fine: the golden comparison is a build-time
 * check of the algorithms, and the algorithms are the same code on every
 * target. Returning null keeps the shared tests compiling and running on
 * Native instead of excluding them, which would hide a real compile break in
 * the common source set.
 */
actual fun readFixtureFileOrNull(name: String, file: String): String? = null

actual fun corpusIsRequired(): Boolean = false

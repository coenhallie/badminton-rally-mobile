package com.badmintontracker.analysis.corpus

actual fun readFixtureFileOrNull(name: String, file: String): String? {
    val stream = object {}.javaClass.getResourceAsStream("/corpus/$name/$file") ?: return null
    return stream.bufferedReader().use { it.readText() }
}

actual fun corpusIsRequired(): Boolean =
    System.getenv("ANALYSIS_REQUIRE_CORPUS") == "1"

actual fun readResourceBytesOrNull(path: String): ByteArray? =
    object {}.javaClass.getResourceAsStream(path)?.use { it.readBytes() }

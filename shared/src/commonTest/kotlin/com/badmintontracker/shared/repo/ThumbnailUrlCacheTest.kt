package com.badmintontracker.shared.repo

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

class ThumbnailUrlCacheTest {
    private var clock = Instant.parse("2026-09-25T10:00:00Z")
    private val cache = ThumbnailUrlCache(ttl = 50.minutes, now = { clock })
    private var signs = 0
    private val sign: suspend (String) -> String? = { path -> signs++; "https://x/$path?t=$signs" }

    @Test
    fun a_path_is_signed_once_while_its_url_is_fresh() = runTest {
        cache.get("a.jpg", sign) shouldBe "https://x/a.jpg?t=1"
        clock = clock.plus(49.minutes)
        cache.get("a.jpg", sign) shouldBe "https://x/a.jpg?t=1"
        signs shouldBe 1
    }

    @Test
    fun a_url_is_signed_again_before_the_one_hour_signature_lapses() = runTest {
        cache.get("a.jpg", sign)
        clock = clock.plus(51.minutes)
        cache.get("a.jpg", sign) shouldBe "https://x/a.jpg?t=2"
    }

    @Test
    fun a_failed_signature_is_not_remembered() = runTest {
        cache.get("a.jpg") { null } shouldBe null
        cache.get("a.jpg", sign) shouldBe "https://x/a.jpg?t=1"
    }
}

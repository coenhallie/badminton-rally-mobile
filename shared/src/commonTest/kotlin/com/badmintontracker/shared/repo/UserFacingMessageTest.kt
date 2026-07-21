package com.badmintontracker.shared.repo

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class UserFacingMessageTest {

    @Test
    fun keeps_short_meaningful_messages() {
        IllegalStateException("Not signed in")
            .userFacingMessage("fallback") shouldBe "Not signed in"
    }

    @Test
    fun replaces_null_and_blank_messages_with_fallback() {
        IllegalStateException().userFacingMessage("fallback") shouldBe "fallback"
        IllegalStateException("  ").userFacingMessage("fallback") shouldBe "fallback"
    }

    @Test
    fun replaces_multiline_dumps_with_fallback() {
        // RestException.message is a multi-line dump (Code/Hint/Details/URL/Headers).
        IllegalStateException("Unknown error\nCode: null\nHint: null\nURL: https://x")
            .userFacingMessage("fallback") shouldBe "fallback"
    }

    @Test
    fun replaces_transport_url_dumps_with_fallback() {
        // supabase-kt HttpRequestException: full request URL, often a blank tail.
        IllegalStateException("HTTP request to https://x.supabase.co/rest/v1/videos (GET) failed with message: ")
            .userFacingMessage("fallback") shouldBe "fallback"
    }
}

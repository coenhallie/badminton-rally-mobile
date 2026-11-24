package com.badmintontracker.shared.invites

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class InviteCodeFormatTest {

    @Test
    fun normalizeInviteCode_strips_hyphens_spaces_and_uppercases() {
        normalizeInviteCode("k7m4-qp9r") shouldBe "K7M4QP9R"
        normalizeInviteCode("  k7m4 qp9r  ") shouldBe "K7M4QP9R"
        normalizeInviteCode("K7M4QP9R") shouldBe "K7M4QP9R"
    }

    @Test
    fun normalizeInviteCode_rejects_ambiguous_chars_and_wrong_length() {
        normalizeInviteCode("0000-0000") shouldBe null // 0 excluded
        normalizeInviteCode("OOOO-OOOO") shouldBe null // O excluded
        normalizeInviteCode("1111-1111") shouldBe null // 1 excluded
        normalizeInviteCode("IIII-LLLL") shouldBe null // I and L excluded
        normalizeInviteCode("K7M4QP9") shouldBe null   // 7 chars
        normalizeInviteCode("K7M4QP9RX") shouldBe null // 9 chars
        normalizeInviteCode("") shouldBe null
    }

    @Test
    fun formatInviteCode_groups_into_4_dash_4() {
        formatInviteCode("k7m4qp9r") shouldBe "K7M4-QP9R"
        formatInviteCode("K7M4-QP9R") shouldBe "K7M4-QP9R"
    }

    @Test
    fun qrPayload_round_trips_and_rejects_unrelated_qr_codes() {
        val payload = qrPayloadForCode("k7m4-qp9r")
        payload shouldBe "shuttl-invite:K7M4QP9R"
        parseQrInvitePayload(payload) shouldBe "K7M4QP9R"
        parseQrInvitePayload("SHUTTL-INVITE:k7m4-qp9r") shouldBe "K7M4QP9R"
        parseQrInvitePayload("K7M4-QP9R") shouldBe "K7M4QP9R"
        parseQrInvitePayload("https://example.com/menu") shouldBe null
        parseQrInvitePayload("shuttl-invite:INVALID0") shouldBe null
    }

    @Test
    fun formatInviteInput_auto_inserts_hyphen_while_typing() {
        formatInviteInput("k7m4q") shouldBe "K7M4-Q"
        formatInviteInput("k7m4-qp9r-extra") shouldBe "K7M4-QP9R"
        formatInviteInput("k7m") shouldBe "K7M"
    }
}

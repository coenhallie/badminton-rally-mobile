package com.badmintontracker.shared.localvideo

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LocalVideoDetailsTest {

    @Test
    fun trims_surrounding_whitespace() {
        normalizeTitle("  Thu League vs Marco  ") shouldBe "Thu League vs Marco"
        normalizeDescription("\n Indoor court 2. \n") shouldBe "Indoor court 2."
    }

    @Test
    fun whitespace_only_input_becomes_null_never_empty_string() {
        // videos_title_length_check rejects "", and that rejection reaches the
        // user as a CREATE_ROW pipeline failure with nothing actionable in it.
        normalizeTitle("").shouldBeNull()
        normalizeTitle("   ").shouldBeNull()
        normalizeDescription("").shouldBeNull()
        normalizeDescription(" \n\t ").shouldBeNull()
    }

    @Test
    fun text_already_at_the_cap_is_untouched() {
        val title = "a".repeat(MAX_MATCH_TITLE_LENGTH)
        normalizeTitle(title) shouldBe title
        val description = "b".repeat(MAX_MATCH_DESCRIPTION_LENGTH)
        normalizeDescription(description) shouldBe description
    }

    @Test
    fun over_long_input_is_truncated_rather_than_rejected() {
        // Matches the web app's matchTitle.trim().slice(0, MAX_TITLE_LENGTH).
        normalizeTitle("a".repeat(MAX_MATCH_TITLE_LENGTH + 40))!!.length shouldBe MAX_MATCH_TITLE_LENGTH
        normalizeDescription("b".repeat(MAX_MATCH_DESCRIPTION_LENGTH + 40))!!.length shouldBe
            MAX_MATCH_DESCRIPTION_LENGTH
    }

    @Test
    fun truncation_never_leaves_a_trailing_space() {
        // Cutting at the cap can land mid-gap; the tail trim is what keeps a
        // pasted title from being stored as "... " with a dangling space.
        val raw = "a".repeat(MAX_MATCH_TITLE_LENGTH - 1) + "  tail"
        normalizeTitle(raw) shouldBe "a".repeat(MAX_MATCH_TITLE_LENGTH - 1)
    }
}

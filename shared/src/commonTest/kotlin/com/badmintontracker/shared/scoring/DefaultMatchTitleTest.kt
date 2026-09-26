package com.badmintontracker.shared.scoring

import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test

class DefaultMatchTitleTest {

    private val amsterdam = TimeZone.of("Europe/Amsterdam")
    private val afternoon = Instant.parse("2026-09-20T12:30:00Z")   // 14:30 in Amsterdam

    @Test
    fun a_match_is_named_for_when_it_is_played() {
        defaultMatchTitle(afternoon, amsterdam, place = null) shouldBe "Sep 20, 14:30"
    }

    @Test
    fun the_time_is_the_phones_own_and_not_utc() {
        defaultMatchTitle(afternoon, TimeZone.UTC, place = null) shouldBe "Sep 20, 12:30"
    }

    @Test
    fun single_digit_hours_and_minutes_are_padded() {
        defaultMatchTitle(Instant.parse("2026-01-05T07:05:00Z"), TimeZone.UTC, place = null) shouldBe "Jan 5, 07:05"
    }

    @Test
    fun a_known_place_is_added_to_the_name() {
        defaultMatchTitle(afternoon, amsterdam, place = "Amsterdam") shouldBe "Sep 20, 14:30 - Amsterdam"
    }

    @Test
    fun a_blank_place_is_no_place() {
        defaultMatchTitle(afternoon, amsterdam, place = "  ") shouldBe "Sep 20, 14:30"
    }

    @Test
    fun a_long_place_is_cut_so_the_name_still_fits() {
        // The form opens with this already filled in, so it must never open with
        // "The match name can be up to 80 characters" showing.
        val title = defaultMatchTitle(afternoon, amsterdam, place = "x".repeat(200))
        title.length shouldBe MAX_MATCH_TITLE
        createMatchProblem(title) shouldBe null
    }
}

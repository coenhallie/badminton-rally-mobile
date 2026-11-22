package com.badmintontracker.shared.players

import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlin.test.Test

class PlayerTest {

    private fun player(id: String, name: String) = Player(
        id = id,
        ownerId = "owner",
        name = name,
        createdAt = Instant.parse("2026-10-31T12:00:00Z"),
        updatedAt = Instant.parse("2026-10-31T12:00:00Z"),
    )

    @Test
    fun initials_take_the_first_and_last_word() {
        initials("Marco Vidal") shouldBe "MV"
        initials("  marco   de la vidal ") shouldBe "MV"
    }

    @Test
    fun initials_skip_a_trailing_suffix() {
        // "Junior" is not a surname: the avatar read "BJ" for this name.
        initials("Bartholomew Montgomery-Fitzgerald Junior") shouldBe "BM"
        initials("Ken Griffey Jr.") shouldBe "KG"
        initials("Martin Luther King, Jr") shouldBe "MK"
        initials("Henry Ford II") shouldBe "HF"
        initials("Pedro Silva sr") shouldBe "PS"
    }

    @Test
    fun a_two_word_name_keeps_its_second_word_even_when_it_reads_as_a_suffix() {
        // Two words are a first name and a surname; "Junior" can be either.
        initials("Marco Junior") shouldBe "MJ"
        initials("Junior Silva") shouldBe "JS"
    }

    @Test
    fun initials_of_one_word_is_one_letter() {
        initials("Marco") shouldBe "M"
        initials("  ") shouldBe ""
    }

    @Test
    fun a_blank_name_is_a_problem() {
        playerNameProblem("   ") shouldBe "Enter a name."
        playerNameProblem("") shouldBe "Enter a name."
        playerNameProblem("Marco") shouldBe null
    }

    @Test
    fun a_41_character_name_is_a_problem() {
        playerNameProblem("a".repeat(40)) shouldBe null
        playerNameProblem("a".repeat(41)) shouldBe "A player name can be up to 40 characters."
        // The cap counts the trimmed name, as the database CHECK does.
        playerNameProblem("  " + "a".repeat(40) + "  ") shouldBe null
    }

    @Test
    fun players_sort_by_name_ignoring_case() {
        val sorted = sortedPlayers(
            listOf(player("3", "zoe"), player("2", "Bea"), player("1", "alex"), player("0", "bea")),
        )
        sorted.map { it.id } shouldBe listOf("1", "0", "2", "3")
    }

    @Test
    fun a_failure_shows_the_fixed_sentence_unless_it_is_a_name_problem() {
        PlayersCopy.failureMessage(RuntimeException("HTTP request to https://x failed"), PlayersCopy.SAVE_FAILED) shouldBe
            PlayersCopy.SAVE_FAILED
        PlayersCopy.failureMessage(IllegalArgumentException("Unexpected JSON token"), PlayersCopy.SAVE_FAILED) shouldBe
            PlayersCopy.SAVE_FAILED
        PlayersCopy.failureMessage(IllegalArgumentException("Enter a name."), PlayersCopy.SAVE_FAILED) shouldBe
            "Enter a name."
        PlayersCopy.failureMessage(
            IllegalArgumentException("A player name can be up to 40 characters."),
            PlayersCopy.SAVE_FAILED,
        ) shouldBe "A player name can be up to 40 characters."
    }
}

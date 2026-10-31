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
}

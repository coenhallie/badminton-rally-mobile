package com.badmintontracker.shared.exercises

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ExerciseCatalogTest {

    private val expectedIds = listOf(
        "base-mark-6-corner-shadow",
        "clear-to-base-multi-shuttle",
        "net-to-rear-recovery",
        "front-back-2-corner-feed",
        "opposite-side-corner-recovery",
        "centre-line-split-reset",
        "fatigue-6-corner-shadow",
        "split-squat-endurance",
        "cone-touch-base-discipline",
        "drop-and-follow-net-attack",
        "forecourt-lunge-net-kill",
        "repeat-burst-multi-shuttle",
        "lateral-bounds-split-jumps",
    )

    @Test
    fun catalog_contains_all_thirteen_unique_exercises() {
        ExerciseCatalog.all shouldHaveSize 13
        ExerciseCatalog.all.map { it.id }.distinct() shouldHaveSize 13
        ExerciseCatalog.all.count { it.category == ExerciseCategory.ON_COURT } shouldBe 11
        ExerciseCatalog.all.count { it.category == ExerciseCategory.OFF_COURT } shouldBe 2
        expectedIds.forEach { id ->
            ExerciseCatalog.byId(id).id shouldBe id
        }
    }

    @Test
    fun every_exercise_has_complete_concise_copy() {
        ExerciseCatalog.all.forEach { exercise ->
            exercise.title.isNotBlank() shouldBe true
            exercise.prescription.isNotBlank() shouldBe true
            exercise.setup.isNotBlank() shouldBe true
            exercise.coachingCue.isNotBlank() shouldBe true
            exercise.steps.size shouldBeInRange 3..4
            exercise.steps.all { it.isNotBlank() } shouldBe true
        }
    }

    @Test
    fun catalog_and_copy_contain_no_em_dashes_or_player_pronouns() {
        val pronounRegex = Regex("""\b(he|she|his|her|they|their)\b""", RegexOption.IGNORE_CASE)
        val strings = buildList {
            ExerciseCatalog.all.forEach { ex ->
                add(ex.title)
                add(ex.prescription)
                add(ex.setup)
                add(ex.coachingCue)
                addAll(ex.steps)
            }
            add(ExercisesCopy.SUMMARY_HEADING)
            add(ExercisesCopy.SUGGESTIONS_HEADING)
            add(ExercisesCopy.PEAK_SPEED_LABEL)
            add(ExercisesCopy.SETUP_LABEL)
            add(ExercisesCopy.CUE_LABEL)
            add(ExercisesCopy.HONESTY_CAPTION)
            add(ExercisesCopy.NOT_LOADED)
            add(ExercisesCopy.NO_BOUNDED_WINDOWS)
            add(ExercisesCopy.NO_COUNTED_RALLIES)
            add(ExercisesCopy.ralliesCaption(84))
            add(ExercisesCopy.zoneBreakdown(15, 55, 30))
            add(ExercisesCopy.insufficientData(2, 71, 4, 50))
            add(ExercisesCopy.balanced(9))
        }
        strings.forEach { text ->
            text.contains('\u2014') shouldBe false
            pronounRegex.containsMatchIn(text) shouldBe false
        }
    }
}

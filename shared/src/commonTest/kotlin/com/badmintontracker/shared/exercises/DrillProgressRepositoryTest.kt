package com.badmintontracker.shared.exercises

import com.russhwolf.settings.MapSettings
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class DrillProgressRepositoryTest {

    @Test
    fun records_persists_filters_and_deletes_sessions() {
        val settings = MapSettings()
        var clock = 1_750_000_000_000L
        val repo = DrillProgressRepository(settings) { clock }

        val first = repo.recordSession(
            exerciseId = "base-mark-6-corner-shadow",
            playerId = "p1",
            matchId = "m1",
            setsCompleted = 4,
            setsTarget = 4,
            durationSeconds = 240,
            effort = DrillEffort.SOLID,
            note = "Clean split-step",
        )
        clock += 86_400_000L * 2
        val second = repo.recordSession(
            exerciseId = "base-mark-6-corner-shadow",
            playerId = "p1",
            matchId = null,
            setsCompleted = 4,
            setsTarget = 4,
            durationSeconds = 250,
            effort = DrillEffort.HARD,
            note = "",
        )
        repo.recordSession(
            exerciseId = "jump-smash-and-follow",
            playerId = "p2",
            matchId = null,
            setsCompleted = 3,
            setsTarget = 4,
            durationSeconds = 180,
            effort = DrillEffort.EASY,
            note = "",
        )

        // Reload from same backing Settings to prove JSON persistence.
        val reloaded = DrillProgressRepository(settings) { clock }
        reloaded.logs.value shouldHaveSize 3

        val p1Logs = filterDrillLogs(reloaded.logs.value, "p1")
        p1Logs shouldHaveSize 2
        p1Logs.first().id shouldBe second.id

        val summary = progressForExercise(p1Logs, "base-mark-6-corner-shadow", clock)
        summary.totalSessions shouldBe 2
        summary.completedToday shouldBe true
        summary.lastEffort shouldBe DrillEffort.HARD
        exerciseProgressBadge(summary, clock) shouldBe "Done today · 2x"

        val untouched = progressForExercise(p1Logs, "split-squat-endurance", clock)
        exerciseProgressBadge(untouched, clock).shouldBeNull()

        val overview = trainingOverview(p1Logs, clock)
        overview.sessionsThisWeek shouldBe 2
        overview.totalMinutesThisWeek shouldBe 8
        overview.uniqueDrillsTried shouldBe 1
        trainingOverviewHeadline(overview) shouldBe "2 sessions this week · 8 min"

        reloaded.deleteSession(first.id)
        reloaded.logs.value shouldHaveSize 2
    }

    @Test
    fun relative_day_labels_and_corrupted_storage_behave_safely() {
        val day = 86_400_000L
        val now = 20_000L * day + 3600_000L
        relativeDaysLabel(now - 1000L, now) shouldBe "Today"
        relativeDaysLabel(now - day, now) shouldBe "Yesterday"
        relativeDaysLabel(now - 4 * day, now) shouldBe "4d ago"

        val badSettings = MapSettings()
        badSettings.putString("drill_session_logs_v1", "{not valid json")
        val repo = DrillProgressRepository(badSettings) { now }
        repo.logs.value shouldHaveSize 0
    }
}

package com.badmintontracker.shared.exercises

import com.russhwolf.settings.MapSettings
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
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

        val badgeMap = badgeMapByExercise(p1Logs, clock)
        badgeMap["base-mark-6-corner-shadow"] shouldBe "Done today · 2x"
        badgeMap["split-squat-endurance"].shouldBeNull()

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
    fun local_timezone_day_buckets_respect_local_midnight_instead_of_utc() {
        val est = TimeZone.of("UTC-5")
        // 2026-06-15 18:00 EST (23:00 UTC) and 20:00 EST (next day 01:00 UTC).
        val sixPmEst = Instant.parse("2026-06-15T23:00:00Z").toEpochMilliseconds()
        val eightPmEst = Instant.parse("2026-06-16T01:00:00Z").toEpochMilliseconds()

        // Same evening in UTC-5 is still "Today", whereas UTC would have flipped to "Yesterday".
        relativeDaysLabelInZone(sixPmEst, eightPmEst, est) shouldBe "Today"
        relativeDaysLabelInZone(sixPmEst, eightPmEst, TimeZone.UTC) shouldBe "Yesterday"

        val log = DrillSessionLog(
            id = "l1",
            exerciseId = "base-mark-6-corner-shadow",
            completedAtEpochMs = sixPmEst,
            setsCompleted = 4,
            setsTarget = 4,
            durationSeconds = 200,
            effort = DrillEffort.SOLID,
        )
        val badgesEst = badgeMapByExerciseInZone(listOf(log), eightPmEst, est)
        badgesEst["base-mark-6-corner-shadow"] shouldBe "Done today"
        val badgesUtc = badgeMapByExerciseInZone(listOf(log), eightPmEst, TimeZone.UTC)
        badgesUtc["base-mark-6-corner-shadow"] shouldBe "Done 1x · Yesterday"
    }

    @Test
    fun saves_and_reloads_custom_drill_plans_and_filters_recent_and_search() {
        val settings = MapSettings()
        var clock = 1_750_000_000_000L
        val repo = DrillProgressRepository(settings) { clock }
        val shadow = ExerciseCatalog.byId("base-mark-6-corner-shadow")

        repo.savedPlanFor(shadow.id).shouldBeNull()
        val custom = defaultPlanFor(shadow).copy(sets = 6, workSeconds = 50, restSeconds = 25)
        repo.savePlan(shadow.id, custom)

        val reloaded = DrillProgressRepository(settings) { clock }
        reloaded.planForExercise(shadow) shouldBe custom
        val session = createDrillSessionWithPlan(shadow, reloaded.planForExercise(shadow))
        session.plan.sets shouldBe 6
        session.plan.workSeconds shouldBe 50

        reloaded.recordSession(shadow.id, "p1", null, 6, 6, 300, DrillEffort.SOLID, "")
        clock += 1000L
        reloaded.recordSession("jump-smash-and-follow", "p1", null, 3, 3, 180, DrillEffort.HARD, "")
        ExerciseCatalog.recentExercises(reloaded.logs.value).map { it.id } shouldContainExactly listOf(
            "jump-smash-and-follow",
            shadow.id,
        )

        val matches = ExerciseCatalog.search(ExerciseCatalog.all, "6-corner shadow")
        matches.shouldNotBeEmpty()
        matches.any { it.id == shadow.id } shouldBe true
        ExerciseCatalog.byIdOrNull("does-not-exist").shouldBeNull()
        ExerciseCatalog.filterCount(DrillLibraryFilter.ALL) shouldBe ExerciseCatalog.all.size
    }

    @Test
    fun relative_day_labels_and_corrupted_storage_behave_safely() {
        val day = 86_400_000L
        val now = 20_000L * day + 3600_000L
        relativeDaysLabelInZone(now - 1000L, now, TimeZone.UTC) shouldBe "Today"
        relativeDaysLabelInZone(now - day, now, TimeZone.UTC) shouldBe "Yesterday"
        relativeDaysLabelInZone(now - 4 * day, now, TimeZone.UTC) shouldBe "4d ago"

        val badSettings = MapSettings()
        badSettings.putString("drill_session_logs_v1", "{not valid json")
        badSettings.putString("drill_custom_plans_v1", "{also bad")
        val repo = DrillProgressRepository(badSettings) { now }
        repo.logs.value shouldHaveSize 0
        repo.savedPlanFor("base-mark-6-corner-shadow").shouldBeNull()
    }
}

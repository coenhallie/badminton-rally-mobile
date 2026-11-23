package com.badmintontracker.shared.exercises

import com.badmintontracker.shared.players.PlayersCopy
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class DrillSessionTest {

    @Test
    fun every_catalog_exercise_produces_a_valid_structured_plan() {
        ExerciseCatalog.all.forEach { ex ->
            val plan = defaultPlanFor(ex)
            plan.sets shouldBeGreaterThanOrEqual 2
            plan.restSeconds shouldBeGreaterThanOrEqual 0
            if (plan.mode == DrillWorkMode.TIMED) {
                plan.workSeconds shouldBeGreaterThan 0
                plan.readySeconds shouldBe 5
            } else {
                plan.targetReps shouldBeGreaterThan 0
                plan.repUnit.isNotBlank() shouldBe true
            }
            formatPlanSummary(plan).isNotBlank() shouldBe true
        }
    }

    @Test
    fun timed_and_rep_prescriptions_parse_accurately() {
        val timed = defaultPlanFor(ExerciseCatalog.byId("base-mark-6-corner-shadow"))
        timed.mode shouldBe DrillWorkMode.TIMED
        timed.sets shouldBe 4
        timed.workSeconds shouldBe 45
        timed.restSeconds shouldBe 20

        val alactic = defaultPlanFor(ExerciseCatalog.byId("cone-box-alactic-intervals"))
        alactic.mode shouldBe DrillWorkMode.TIMED
        alactic.sets shouldBe 8
        alactic.workSeconds shouldBe 8
        alactic.restSeconds shouldBe 40

        val shuttles = defaultPlanFor(ExerciseCatalog.byId("clear-to-base-multi-shuttle"))
        shuttles.mode shouldBe DrillWorkMode.REPS
        shuttles.sets shouldBe 3
        shuttles.targetReps shouldBe 15
        shuttles.repUnit shouldBe "shuttles"

        val splitSquat = defaultPlanFor(ExerciseCatalog.byId("split-squat-endurance"))
        splitSquat.mode shouldBe DrillWorkMode.REPS
        splitSquat.sets shouldBe 3
        splitSquat.targetReps shouldBe 12
        splitSquat.repUnit shouldBe "reps / leg"
        splitSquat.restSeconds shouldBe 45

        val rally20 = defaultPlanFor(ExerciseCatalog.byId("half-court-consistency-20"))
        rally20.mode shouldBe DrillWorkMode.REPS
        rally20.sets shouldBe 4
        rally20.targetReps shouldBe 20
        rally20.repUnit shouldBe "shots"
    }

    @Test
    fun timed_session_auto_advances_ready_work_rest_and_summary_with_cues() {
        val base = createDrillSession(ExerciseCatalog.byId("base-mark-6-corner-shadow"))
        val custom = base.copy(
            plan = base.plan.copy(sets = 2, workSeconds = 4, restSeconds = 3, readySeconds = 2),
        )
        var t = startDrillSession(custom)
        t.state.phase shouldBe DrillPhase.GET_READY
        t.state.remainingSeconds shouldBe 2
        t.cue shouldBe DrillCueEvent.COUNTDOWN_TICK

        t = tickDrillSession(t.state)
        t.state.remainingSeconds shouldBe 1
        t.cue shouldBe DrillCueEvent.COUNTDOWN_TICK

        t = tickDrillSession(t.state)
        t.state.phase shouldBe DrillPhase.WORK
        t.state.currentSet shouldBe 1
        t.state.remainingSeconds shouldBe 4
        t.cue shouldBe DrillCueEvent.WORK_START

        // Tick down set 1 (4 -> 3 -> 2 -> 1 -> REST)
        t = tickDrillSession(t.state)
        t.state.remainingSeconds shouldBe 3
        t.cue shouldBe DrillCueEvent.COUNTDOWN_TICK
        t = tickDrillSession(t.state)
        t = tickDrillSession(t.state)
        t = tickDrillSession(t.state)
        t.state.phase shouldBe DrillPhase.REST
        t.state.completedSets shouldBe 1
        t.state.remainingSeconds shouldBe 3
        t.cue shouldBe DrillCueEvent.REST_START

        // Extend rest by 15 s then skip directly to Set 2
        val extended = extendRest(t.state, 15)
        extended.remainingSeconds shouldBe 18
        t = skipRest(extended)
        t.state.phase shouldBe DrillPhase.WORK
        t.state.currentSet shouldBe 2
        t.cue shouldBe DrillCueEvent.WORK_START

        // Complete set 2 -> SUMMARY
        t = completeCurrentSet(t.state)
        t.state.phase shouldBe DrillPhase.SUMMARY
        t.state.completedSets shouldBe 2
        t.cue shouldBe DrillCueEvent.SESSION_COMPLETE
    }

    @Test
    fun rep_session_tracks_elapsed_time_and_respects_pause_and_setup_adjustments() {
        var s = createDrillSession(ExerciseCatalog.byId("clear-to-base-multi-shuttle"))
        s = adjustPlanSets(s, 1)
        s = adjustPlanWork(s, 5)
        s = adjustPlanRest(s, -10)
        s.plan.sets shouldBe 4
        s.plan.targetReps shouldBe 20
        s.plan.restSeconds shouldBe 20

        var t = startDrillSession(s)
        t.state.phase shouldBe DrillPhase.WORK
        t.cue shouldBe DrillCueEvent.WORK_START

        t = tickDrillSession(t.state)
        t = tickDrillSession(t.state)
        t.state.elapsedSeconds shouldBe 2

        val paused = toggleSessionPause(t.state)
        paused.paused shouldBe true
        tickDrillSession(paused).state.elapsedSeconds shouldBe 2

        val early = finishSessionEarly(t.state)
        early.state.phase shouldBe DrillPhase.SUMMARY
        early.cue shouldBe DrillCueEvent.SESSION_COMPLETE

        val readyStart = startDrillSession(createDrillSession(ExerciseCatalog.byId("base-mark-6-corner-shadow")))
        readyStart.state.phase shouldBe DrillPhase.GET_READY
        val skipped = skipGetReady(readyStart.state)
        skipped.state.phase shouldBe DrillPhase.WORK
        skipped.state.currentSet shouldBe 1
        skipped.cue shouldBe DrillCueEvent.WORK_START
    }

    @Test
    fun clock_formatting_and_copy_stay_clean() {
        formatClockSeconds(0) shouldBe "0:00"
        formatClockSeconds(7) shouldBe "0:07"
        formatClockSeconds(65) shouldBe "1:05"
        formatClockSeconds(-5) shouldBe "0:00"

        val pronounRegex = Regex("""\b(he|she|his|her|they|their)\b""", RegexOption.IGNORE_CASE)
        val strings = listOf(
            ExercisesCopy.TRAINING_LOG_HEADING,
            ExercisesCopy.START_DRILL,
            ExercisesCopy.START_SESSION,
            ExercisesCopy.COMPLETE_SET,
            ExercisesCopy.SKIP_REST,
            ExercisesCopy.ADD_REST_15,
            ExercisesCopy.FINISH_EARLY,
            ExercisesCopy.SAVE_SESSION,
            ExercisesCopy.DISCARD_SESSION,
            ExercisesCopy.HOW_DID_IT_FEEL,
            ExercisesCopy.NOTE_PLACEHOLDER,
            ExercisesCopy.SESSION_COMPLETE_TITLE,
            ExercisesCopy.RECENT_SESSIONS_LABEL,
            ExercisesCopy.NO_SESSIONS_YET,
            ExercisesCopy.setProgressLabel(2, 4),
            ExercisesCopy.completeSetButton(2, 4),
            PlayersCopy.TAB_DRILLS,
        ) + DrillEffort.entries.map { it.label }
        strings.forEach { str ->
            str.contains('\u2014') shouldBe false
            pronounRegex.containsMatchIn(str) shouldBe false
        }
    }
}

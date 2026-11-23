package com.badmintontracker.shared.exercises

import kotlinx.serialization.Serializable

/** Self-rated effort logged at the end of a completed drill session. */
@Serializable
enum class DrillEffort(val label: String) {
    EASY("Easy"),
    SOLID("Solid"),
    HARD("Hard"),
}

/** Whether each set runs on an automatic countdown timer or a self-paced rep/shuttle target. */
@Serializable
enum class DrillWorkMode {
    TIMED,
    REPS,
}

/** Structured volume and interval plan derived from an [Exercise] and customizable before start. */
@Serializable
data class DrillPlan(
    val sets: Int,
    val mode: DrillWorkMode,
    /** Work duration per set in seconds when [mode] is [DrillWorkMode.TIMED]. */
    val workSeconds: Int,
    /** Target count per set when [mode] is [DrillWorkMode.REPS]. */
    val targetReps: Int,
    /** Display unit for rep-based targets (e.g. "shuttles", "reps / leg", "pairs"). */
    val repUnit: String,
    /** Rest countdown between sets in seconds (0 skips rest automatically). */
    val restSeconds: Int,
    /** Short pre-set countdown before Set 1 on timed drills so the player can step into position. */
    val readySeconds: Int,
)

/** Active stage inside the guided drill session runner. */
enum class DrillPhase {
    SETUP,
    GET_READY,
    WORK,
    REST,
    SUMMARY,
}

/** Audio/haptic cue emitted by pure session transitions. */
enum class DrillCueEvent {
    NONE,
    COUNTDOWN_TICK,
    WORK_START,
    REST_START,
    SESSION_COMPLETE,
}

/** Immutable snapshot of a guided drill session shared identically by Android and iOS. */
data class DrillSessionState(
    val exerciseId: String,
    val plan: DrillPlan,
    val phase: DrillPhase,
    val currentSet: Int,
    val completedSets: Int,
    val remainingSeconds: Int,
    val phaseTotalSeconds: Int,
    val elapsedSeconds: Int,
    val paused: Boolean,
)

/** Typed state + audio/haptic cue pair so Swift and Kotlin consume transitions cleanly. */
data class DrillTransition(
    val state: DrillSessionState,
    val cue: DrillCueEvent,
)

/** Ordered effort pills exposed as a plain list for Swift and Compose. */
val allDrillEfforts: List<DrillEffort> = DrillEffort.entries

/** Builds the default structured [DrillPlan] from an [Exercise]'s prescription text. */
fun defaultPlanFor(exercise: Exercise): DrillPlan {
    val text = exercise.prescription
    val sets = Regex("""^(\d+)\s*[x×]""").find(text)?.groupValues?.get(1)?.toIntOrNull()
        ?: Regex("""^(\d+)\s+successful""").find(text)?.groupValues?.get(1)?.toIntOrNull()
        ?: 3

    val rest = Regex("""(\d+)\s*s\s+rest""").find(text)?.groupValues?.get(1)?.toIntOrNull()
        ?: when {
            text.contains("warm-up", ignoreCase = true) -> 15
            exercise.category == ExerciseCategory.OFF_COURT -> 40
            else -> 30
        }

    // Timed work pattern: e.g. "4 x 45 s · 20 s rest", "4 x 35 s hold · 45 s rest", "8 x 8 s max burst · 40 s rest".
    val timedMatch = Regex("""^\d+\s*[x×]\s*(\d+)\s*s\b""").find(text)
    if (timedMatch != null) {
        val workSec = timedMatch.groupValues[1].toInt().coerceIn(5, 300)
        return DrillPlan(
            sets = sets.coerceIn(1, 20),
            mode = DrillWorkMode.TIMED,
            workSeconds = workSec,
            targetReps = 0,
            repUnit = "s",
            restSeconds = rest.coerceIn(0, 180),
            readySeconds = 5,
        )
    }

    // Compound rep pattern: e.g. "4 x 8 bounds + 6 split-jumps", "4 x 12 pogos + 6 split landings".
    val plusMatch = Regex("""^\d+\s*[x×]\s*(\d+)\s+([a-zA-Z]+)\s*\+\s*(\d+)\s+([a-zA-Z -]+)""").find(text)
    if (plusMatch != null) {
        val a = plusMatch.groupValues[1].toInt()
        val b = plusMatch.groupValues[3].toInt()
        return DrillPlan(
            sets = sets.coerceIn(1, 20),
            mode = DrillWorkMode.REPS,
            workSeconds = 0,
            targetReps = (a + b).coerceIn(1, 100),
            repUnit = "reps",
            restSeconds = rest.coerceIn(0, 180),
            readySeconds = 0,
        )
    }

    // Rally-count pattern: e.g. "4 successful 20-shot rallies".
    val rallyMatch = Regex("""(\d+)-shot\s+rallies""").find(text)
    if (rallyMatch != null) {
        return DrillPlan(
            sets = sets.coerceIn(1, 20),
            mode = DrillWorkMode.REPS,
            workSeconds = 0,
            targetReps = rallyMatch.groupValues[1].toInt().coerceIn(1, 100),
            repUnit = "shots",
            restSeconds = rest.coerceIn(0, 180),
            readySeconds = 0,
        )
    }

    // Standard rep/shuttle pattern: "3 x 15 shuttles", "3 x 12 / leg · 45 s rest", "3 x 8 / side · 35 s rest".
    val repMatch = Regex("""^\d+\s*[x×]\s*(\d+)\s*(.*)""").find(text)
    val reps = repMatch?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 100) ?: 12
    val tail = repMatch?.groupValues?.get(2)?.substringBefore('·')?.substringBefore('(')?.trim().orEmpty()
    val unit = when {
        tail.startsWith("/ leg", ignoreCase = true) -> "reps / leg"
        tail.startsWith("/ side", ignoreCase = true) -> "reps / side"
        tail.startsWith("/ arm", ignoreCase = true) -> "reps / arm"
        tail.startsWith("/ hand", ignoreCase = true) -> "reps / hand"
        tail.contains("clocks / leg", ignoreCase = true) -> "clocks / leg"
        tail.contains("shuttle", ignoreCase = true) -> "shuttles"
        tail.contains("pair", ignoreCase = true) -> "pairs"
        tail.contains("serve", ignoreCase = true) -> "serves"
        tail.contains("receive", ignoreCase = true) -> "receives"
        tail.contains("cue", ignoreCase = true) -> "cues"
        tail.contains("cycle", ignoreCase = true) -> "cycles"
        tail.contains("jump", ignoreCase = true) -> "jumps"
        tail.contains("tap", ignoreCase = true) -> "taps"
        else -> "reps"
    }

    return DrillPlan(
        sets = sets.coerceIn(1, 20),
        mode = DrillWorkMode.REPS,
        workSeconds = 0,
        targetReps = reps,
        repUnit = unit,
        restSeconds = rest.coerceIn(0, 180),
        readySeconds = 0,
    )
}

/** Creates a fresh guided session sitting on the [DrillPhase.SETUP] screen with [plan]. */
fun createDrillSessionWithPlan(exercise: Exercise, plan: DrillPlan): DrillSessionState =
    DrillSessionState(
        exerciseId = exercise.id,
        plan = plan,
        phase = DrillPhase.SETUP,
        currentSet = 1,
        completedSets = 0,
        remainingSeconds = if (plan.mode == DrillWorkMode.TIMED) plan.workSeconds else 0,
        phaseTotalSeconds = if (plan.mode == DrillWorkMode.TIMED) plan.workSeconds else 0,
        elapsedSeconds = 0,
        paused = false,
    )

/** Creates a fresh guided session sitting on the [DrillPhase.SETUP] confirmation screen. */
fun createDrillSession(exercise: Exercise): DrillSessionState =
    createDrillSessionWithPlan(exercise, defaultPlanFor(exercise))

/** Adjusts target set count on the SETUP screen. */
fun adjustPlanSets(state: DrillSessionState, delta: Int): DrillSessionState {
    if (state.phase != DrillPhase.SETUP) return state
    val nextSets = (state.plan.sets + delta).coerceIn(1, 15)
    return state.copy(plan = state.plan.copy(sets = nextSets))
}

/** Adjusts work seconds (by 5 s steps) or rep target (by 1-2 steps) on the SETUP screen. */
fun adjustPlanWork(state: DrillSessionState, delta: Int): DrillSessionState {
    if (state.phase != DrillPhase.SETUP) return state
    return if (state.plan.mode == DrillWorkMode.TIMED) {
        val nextWork = (state.plan.workSeconds + delta).coerceIn(5, 180)
        state.copy(
            plan = state.plan.copy(workSeconds = nextWork),
            remainingSeconds = nextWork,
            phaseTotalSeconds = nextWork,
        )
    } else {
        val nextReps = (state.plan.targetReps + delta).coerceIn(1, 60)
        state.copy(plan = state.plan.copy(targetReps = nextReps))
    }
}

/** Adjusts rest interval in seconds on the SETUP screen. */
fun adjustPlanRest(state: DrillSessionState, delta: Int): DrillSessionState {
    if (state.phase != DrillPhase.SETUP) return state
    val nextRest = (state.plan.restSeconds + delta).coerceIn(0, 120)
    return state.copy(plan = state.plan.copy(restSeconds = nextRest))
}

/** Starts an active session from [DrillPhase.SETUP]. */
fun startDrillSession(state: DrillSessionState): DrillTransition {
    val plan = state.plan
    return if (plan.readySeconds > 0) {
        DrillTransition(
            state = state.copy(
                phase = DrillPhase.GET_READY,
                currentSet = 1,
                completedSets = 0,
                remainingSeconds = plan.readySeconds,
                phaseTotalSeconds = plan.readySeconds,
                elapsedSeconds = 0,
                paused = false,
            ),
            cue = DrillCueEvent.COUNTDOWN_TICK,
        )
    } else {
        enterWorkSet(state.copy(elapsedSeconds = 0, completedSets = 0), setNumber = 1)
    }
}

/** Advances an unpaused active session by exactly one second. */
fun tickDrillSession(state: DrillSessionState): DrillTransition {
    if (state.paused || state.phase == DrillPhase.SETUP || state.phase == DrillPhase.SUMMARY) {
        return DrillTransition(state, DrillCueEvent.NONE)
    }

    return when (state.phase) {
        DrillPhase.GET_READY -> {
            val nextRem = state.remainingSeconds - 1
            if (nextRem <= 0) {
                enterWorkSet(state, setNumber = state.currentSet)
            } else {
                DrillTransition(
                    state = state.copy(remainingSeconds = nextRem),
                    cue = if (nextRem <= 3) DrillCueEvent.COUNTDOWN_TICK else DrillCueEvent.NONE,
                )
            }
        }
        DrillPhase.WORK -> {
            val ticked = state.copy(elapsedSeconds = state.elapsedSeconds + 1)
            if (state.plan.mode == DrillWorkMode.REPS) {
                // Self-paced rep set: track elapsed time quietly until the user taps Complete Set.
                DrillTransition(ticked, DrillCueEvent.NONE)
            } else {
                val nextRem = state.remainingSeconds - 1
                if (nextRem <= 0) {
                    completeCurrentSet(ticked)
                } else {
                    DrillTransition(
                        state = ticked.copy(remainingSeconds = nextRem),
                        cue = if (nextRem <= 3) DrillCueEvent.COUNTDOWN_TICK else DrillCueEvent.NONE,
                    )
                }
            }
        }
        DrillPhase.REST -> {
            val ticked = state.copy(elapsedSeconds = state.elapsedSeconds + 1)
            val nextRem = state.remainingSeconds - 1
            if (nextRem <= 0) {
                enterWorkSet(ticked, setNumber = state.currentSet + 1)
            } else {
                DrillTransition(
                    state = ticked.copy(remainingSeconds = nextRem),
                    cue = if (nextRem <= 3) DrillCueEvent.COUNTDOWN_TICK else DrillCueEvent.NONE,
                )
            }
        }
        DrillPhase.SETUP, DrillPhase.SUMMARY -> DrillTransition(state, DrillCueEvent.NONE)
    }
}

/** Marks the active set complete and transitions to REST, next WORK, or SUMMARY. */
fun completeCurrentSet(state: DrillSessionState): DrillTransition {
    if (state.phase != DrillPhase.WORK) return DrillTransition(state, DrillCueEvent.NONE)
    val doneSets = (state.completedSets + 1).coerceAtMost(state.plan.sets)
    if (doneSets >= state.plan.sets) {
        return DrillTransition(
            state = state.copy(
                phase = DrillPhase.SUMMARY,
                completedSets = doneSets,
                remainingSeconds = 0,
                phaseTotalSeconds = 0,
                paused = false,
            ),
            cue = DrillCueEvent.SESSION_COMPLETE,
        )
    }
    return if (state.plan.restSeconds > 0) {
        DrillTransition(
            state = state.copy(
                phase = DrillPhase.REST,
                completedSets = doneSets,
                remainingSeconds = state.plan.restSeconds,
                phaseTotalSeconds = state.plan.restSeconds,
                paused = false,
            ),
            cue = DrillCueEvent.REST_START,
        )
    } else {
        enterWorkSet(state.copy(completedSets = doneSets), setNumber = state.currentSet + 1)
    }
}

/** Skips the pre-set Get Ready countdown and starts Work immediately. */
fun skipGetReady(state: DrillSessionState): DrillTransition {
    if (state.phase != DrillPhase.GET_READY) return DrillTransition(state, DrillCueEvent.NONE)
    return enterWorkSet(state, setNumber = state.currentSet)
}

/** Skips remaining rest time and jumps immediately into the next work set. */
fun skipRest(state: DrillSessionState): DrillTransition {
    if (state.phase != DrillPhase.REST) return DrillTransition(state, DrillCueEvent.NONE)
    return enterWorkSet(state, setNumber = state.currentSet + 1)
}

/** Adds [extraSeconds] to the active rest countdown. */
fun extendRest(state: DrillSessionState, extraSeconds: Int): DrillSessionState {
    if (state.phase != DrillPhase.REST || extraSeconds <= 0) return state
    val nextRem = (state.remainingSeconds + extraSeconds).coerceAtMost(300)
    val nextTotal = maxOf(state.phaseTotalSeconds, nextRem)
    return state.copy(remainingSeconds = nextRem, phaseTotalSeconds = nextTotal)
}

/** Toggles pause/resume during GET_READY, WORK, or REST. */
fun toggleSessionPause(state: DrillSessionState): DrillSessionState {
    if (state.phase == DrillPhase.SETUP || state.phase == DrillPhase.SUMMARY) return state
    return state.copy(paused = !state.paused)
}

/** Ends the session early and opens [DrillPhase.SUMMARY] with sets completed so far. */
fun finishSessionEarly(state: DrillSessionState): DrillTransition {
    return DrillTransition(
        state = state.copy(
            phase = DrillPhase.SUMMARY,
            remainingSeconds = 0,
            phaseTotalSeconds = 0,
            paused = false,
        ),
        cue = DrillCueEvent.SESSION_COMPLETE,
    )
}

/** Formats non-negative seconds as `M:SS` deterministically across JVM and Native. */
fun formatClockSeconds(seconds: Int): String {
    val clamped = seconds.coerceAtLeast(0)
    val mins = clamped / 60
    val secs = clamped % 60
    val secStr = if (secs < 10) "0$secs" else "$secs"
    return "$mins:$secStr"
}

/** Formats a [DrillPlan] into a concise one-line summary for setup and review screens. */
fun formatPlanSummary(plan: DrillPlan): String {
    val setsWord = if (plan.sets == 1) "set" else "sets"
    val workPart = if (plan.mode == DrillWorkMode.TIMED) {
        "${plan.workSeconds} s work"
    } else {
        "${plan.targetReps} ${plan.repUnit}"
    }
    return if (plan.restSeconds > 0) {
        "${plan.sets} $setsWord · $workPart · ${plan.restSeconds} s rest"
    } else {
        "${plan.sets} $setsWord · $workPart"
    }
}

private fun enterWorkSet(state: DrillSessionState, setNumber: Int): DrillTransition {
    val workSec = if (state.plan.mode == DrillWorkMode.TIMED) state.plan.workSeconds else 0
    return DrillTransition(
        state = state.copy(
            phase = DrillPhase.WORK,
            currentSet = setNumber.coerceIn(1, state.plan.sets),
            remainingSeconds = workSec,
            phaseTotalSeconds = workSec,
            paused = false,
        ),
        cue = DrillCueEvent.WORK_START,
    )
}

package com.badmintontracker.shared.exercises

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.math.roundToInt

/** One completed drill practice session stored locally on the device. */
@Serializable
data class DrillSessionLog(
    val id: String,
    val exerciseId: String,
    val playerId: String? = null,
    val matchId: String? = null,
    val completedAtEpochMs: Long,
    val setsCompleted: Int,
    val setsTarget: Int,
    val durationSeconds: Int,
    val effort: DrillEffort,
    val note: String = "",
)

/** Compact completion badge summary for one exercise card. */
data class ExerciseProgressSummary(
    val totalSessions: Int,
    val completedToday: Boolean,
    val lastCompletedAtEpochMs: Long?,
    val lastEffort: DrillEffort?,
)

/** Compact rollup shown at the top of the Drills panel and Player Drills tab. */
data class TrainingOverview(
    val sessionsThisWeek: Int,
    val totalMinutesThisWeek: Int,
    val totalSessions: Int,
    val uniqueDrillsTried: Int,
)

private const val MILLIS_PER_DAY: Long = 86_400_000L
private const val SEVEN_DAYS_MS: Long = 7L * MILLIS_PER_DAY
private const val MAX_STORED_LOGS: Int = 500
private const val MAX_SAVED_PLANS: Int = 128

/**
 * Local-first persistence for completed drill sessions and per-drill custom
 * plans, backed by [Settings].
 *
 * Keyed with optional `playerId` and `matchId` so sessions recorded on this
 * branch work 100% offline with zero backend migration and can sync cleanly to
 * Supabase later without changing the model.
 */
class DrillProgressRepository(
    private val settings: Settings,
    private val nowEpochMs: () -> Long,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val logsState = MutableStateFlow(loadLogs())
    val logs: StateFlow<List<DrillSessionLog>> = logsState.asStateFlow()

    private var savedPlans: Map<String, DrillPlan> = loadPlans()

    /** Records a finished drill session and persists it immediately. */
    fun recordSession(
        exerciseId: String,
        playerId: String?,
        matchId: String?,
        setsCompleted: Int,
        setsTarget: Int,
        durationSeconds: Int,
        effort: DrillEffort,
        note: String,
    ): DrillSessionLog {
        val now = nowEpochMs()
        val existing = logsState.value
        val uniqueSuffix = existing.count { it.completedAtEpochMs == now }
        val entry = DrillSessionLog(
            id = "drill_${now}_$uniqueSuffix",
            exerciseId = exerciseId,
            playerId = playerId,
            matchId = matchId,
            completedAtEpochMs = now,
            setsCompleted = setsCompleted.coerceAtLeast(0),
            setsTarget = setsTarget.coerceAtLeast(1),
            durationSeconds = durationSeconds.coerceAtLeast(0),
            effort = effort,
            note = note.trim(),
        )
        val updated = (listOf(entry) + existing).take(MAX_STORED_LOGS)
        persistLogs(updated)
        return entry
    }

    /** Deletes one logged session by [logId]. */
    fun deleteSession(logId: String) {
        val updated = logsState.value.filterNot { it.id == logId }
        if (updated.size != logsState.value.size) {
            persistLogs(updated)
        }
    }

    /** Returns the previously customized [DrillPlan] for [exerciseId], or null if never customized. */
    fun savedPlanFor(exerciseId: String): DrillPlan? = savedPlans[exerciseId]

    /**
     * Returns the saved custom [DrillPlan] for [exercise] when compatible with
     * its work mode, falling back to [defaultPlanFor].
     */
    fun planForExercise(exercise: Exercise): DrillPlan {
        val defaultPlan = defaultPlanFor(exercise)
        val saved = savedPlans[exercise.id] ?: return defaultPlan
        return if (saved.mode == defaultPlan.mode) saved else defaultPlan
    }

    /** Persists a customized [DrillPlan] for [exerciseId] so future runs pre-fill it. */
    fun savePlan(exerciseId: String, plan: DrillPlan) {
        if (savedPlans[exerciseId] == plan) return
        val next = LinkedHashMap<String, DrillPlan>(savedPlans.size + 1)
        next.putAll(savedPlans)
        next[exerciseId] = plan
        while (next.size > MAX_SAVED_PLANS) {
            val oldestKey = next.keys.firstOrNull() ?: break
            next.remove(oldestKey)
        }
        persistPlans(next)
    }

    private fun persistLogs(list: List<DrillSessionLog>) {
        settings.putString(KEY_DRILL_LOGS, json.encodeToString(list))
        logsState.value = list
    }

    private fun loadLogs(): List<DrillSessionLog> {
        val raw = settings.getStringOrNull(KEY_DRILL_LOGS) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<DrillSessionLog>>(raw)
                .sortedByDescending { it.completedAtEpochMs }
        }.getOrDefault(emptyList())
    }

    private fun persistPlans(plans: Map<String, DrillPlan>) {
        settings.putString(KEY_DRILL_PLANS, json.encodeToString(plans))
        savedPlans = plans
    }

    private fun loadPlans(): Map<String, DrillPlan> {
        val raw = settings.getStringOrNull(KEY_DRILL_PLANS) ?: return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, DrillPlan>>(raw)
        }.getOrDefault(emptyMap())
    }

    private companion object {
        const val KEY_DRILL_LOGS = "drill_session_logs_v1"
        const val KEY_DRILL_PLANS = "drill_custom_plans_v1"
    }
}

private fun localEpochDay(epochMs: Long, timeZone: TimeZone): Long =
    Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone).date.toEpochDays().toLong()

/** Filters logs to [playerId] when provided, or returns all logs when [playerId] is null. */
fun filterDrillLogs(logs: List<DrillSessionLog>, playerId: String?): List<DrillSessionLog> =
    if (playerId == null) logs else logs.filter { it.playerId == playerId }

/** Distinct exercise IDs ordered by most recent logged session first. */
fun recentExerciseIds(logs: List<DrillSessionLog>): List<String> {
    if (logs.isEmpty()) return emptyList()
    val seen = LinkedHashSet<String>()
    for (log in logs.sortedByDescending { it.completedAtEpochMs }) {
        seen.add(log.exerciseId)
    }
    return seen.toList()
}

/**
 * Single-pass O(N) progress index keyed by `exerciseId`, bucketed in [timeZone]
 * so "Done today" follows the player's local midnight instead of UTC.
 */
fun progressMapByExerciseInZone(
    logs: List<DrillSessionLog>,
    nowEpochMs: Long,
    timeZone: TimeZone,
): Map<String, ExerciseProgressSummary> {
    if (logs.isEmpty()) return emptyMap()
    val todayDay = localEpochDay(nowEpochMs, timeZone)
    val counts = HashMap<String, Int>()
    val latestById = HashMap<String, DrillSessionLog>()
    for (log in logs) {
        counts[log.exerciseId] = (counts[log.exerciseId] ?: 0) + 1
        val prev = latestById[log.exerciseId]
        if (prev == null || log.completedAtEpochMs > prev.completedAtEpochMs) {
            latestById[log.exerciseId] = log
        }
    }
    val out = HashMap<String, ExerciseProgressSummary>(latestById.size)
    for ((exerciseId, latest) in latestById) {
        val latestDay = localEpochDay(latest.completedAtEpochMs, timeZone)
        out[exerciseId] = ExerciseProgressSummary(
            totalSessions = counts[exerciseId] ?: 0,
            completedToday = latestDay == todayDay,
            lastCompletedAtEpochMs = latest.completedAtEpochMs,
            lastEffort = latest.effort,
        )
    }
    return out
}

/** Single-pass O(N) progress index using the device's local timezone (Swift-friendly). */
fun progressMapByExercise(
    logs: List<DrillSessionLog>,
    nowEpochMs: Long,
): Map<String, ExerciseProgressSummary> =
    progressMapByExerciseInZone(logs, nowEpochMs, TimeZone.currentSystemDefault())

/**
 * Single-pass O(N) badge map (`exerciseId -> "Done today · 2x"`), bucketed in
 * [timeZone], for O(1) lookup per card in `LazyColumn` / `LazyVStack`.
 */
fun badgeMapByExerciseInZone(
    logs: List<DrillSessionLog>,
    nowEpochMs: Long,
    timeZone: TimeZone,
): Map<String, String> {
    val summaries = progressMapByExerciseInZone(logs, nowEpochMs, timeZone)
    if (summaries.isEmpty()) return emptyMap()
    val out = HashMap<String, String>(summaries.size)
    for ((exerciseId, summary) in summaries) {
        val badge = exerciseProgressBadgeInZone(summary, nowEpochMs, timeZone)
        if (badge != null) out[exerciseId] = badge
    }
    return out
}

/** Single-pass O(N) badge map using the device's local timezone (Swift-friendly). */
fun badgeMapByExercise(
    logs: List<DrillSessionLog>,
    nowEpochMs: Long,
): Map<String, String> =
    badgeMapByExerciseInZone(logs, nowEpochMs, TimeZone.currentSystemDefault())

/** Computes badge stats for [exerciseId] from [logs] in [timeZone]. */
fun progressForExerciseInZone(
    logs: List<DrillSessionLog>,
    exerciseId: String,
    nowEpochMs: Long,
    timeZone: TimeZone,
): ExerciseProgressSummary {
    var count = 0
    var latest: DrillSessionLog? = null
    for (log in logs) {
        if (log.exerciseId == exerciseId) {
            count++
            if (latest == null || log.completedAtEpochMs > latest.completedAtEpochMs) {
                latest = log
            }
        }
    }
    if (latest == null) {
        return ExerciseProgressSummary(
            totalSessions = 0,
            completedToday = false,
            lastCompletedAtEpochMs = null,
            lastEffort = null,
        )
    }
    val todayDay = localEpochDay(nowEpochMs, timeZone)
    val latestDay = localEpochDay(latest.completedAtEpochMs, timeZone)
    return ExerciseProgressSummary(
        totalSessions = count,
        completedToday = latestDay == todayDay,
        lastCompletedAtEpochMs = latest.completedAtEpochMs,
        lastEffort = latest.effort,
    )
}

/** Computes badge stats for [exerciseId] from [logs] in the device's local timezone. */
fun progressForExercise(
    logs: List<DrillSessionLog>,
    exerciseId: String,
    nowEpochMs: Long,
): ExerciseProgressSummary =
    progressForExerciseInZone(logs, exerciseId, nowEpochMs, TimeZone.currentSystemDefault())

/** Computes weekly and all-time training totals from [logs] relative to [nowEpochMs]. */
fun trainingOverview(logs: List<DrillSessionLog>, nowEpochMs: Long): TrainingOverview {
    val cutoff = nowEpochMs - SEVEN_DAYS_MS
    var weeklyCount = 0
    var weeklySeconds = 0
    val uniqueIds = HashSet<String>()
    for (log in logs) {
        uniqueIds.add(log.exerciseId)
        if (log.completedAtEpochMs >= cutoff) {
            weeklyCount++
            weeklySeconds += log.durationSeconds
        }
    }
    val weeklyMinutes = if (weeklySeconds <= 0) 0 else max(1, (weeklySeconds / 60.0).roundToInt())
    return TrainingOverview(
        sessionsThisWeek = weeklyCount,
        totalMinutesThisWeek = weeklyMinutes,
        totalSessions = logs.size,
        uniqueDrillsTried = uniqueIds.size,
    )
}

/** Short completion pill text shown on an exercise card in [timeZone], or null if never logged. */
fun exerciseProgressBadgeInZone(
    summary: ExerciseProgressSummary,
    nowEpochMs: Long,
    timeZone: TimeZone,
): String? {
    if (summary.totalSessions <= 0) return null
    if (summary.completedToday) {
        return if (summary.totalSessions == 1) "Done today" else "Done today · ${summary.totalSessions}x"
    }
    val lastMs = summary.lastCompletedAtEpochMs ?: return "Done ${summary.totalSessions}x"
    val ago = relativeDaysLabelInZone(lastMs, nowEpochMs, timeZone)
    return "Done ${summary.totalSessions}x · $ago"
}

/** Short completion pill text using the device's local timezone (Swift-friendly). */
fun exerciseProgressBadge(
    summary: ExerciseProgressSummary,
    nowEpochMs: Long,
): String? =
    exerciseProgressBadgeInZone(summary, nowEpochMs, TimeZone.currentSystemDefault())

/** Relative day label such as "Today", "Yesterday", or "3d ago" in [timeZone]. */
fun relativeDaysLabelInZone(
    timestampEpochMs: Long,
    nowEpochMs: Long,
    timeZone: TimeZone,
): String {
    val diffDays = (localEpochDay(nowEpochMs, timeZone) - localEpochDay(timestampEpochMs, timeZone))
        .coerceAtLeast(0L)
    return when (diffDays) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> "${diffDays}d ago"
    }
}

/** Relative day label using the device's local timezone (Swift-friendly). */
fun relativeDaysLabel(timestampEpochMs: Long, nowEpochMs: Long): String =
    relativeDaysLabelInZone(timestampEpochMs, nowEpochMs, TimeZone.currentSystemDefault())

/** Compact weekly training summary headline. */
fun trainingOverviewHeadline(overview: TrainingOverview): String {
    val sessionWord = if (overview.sessionsThisWeek == 1) "session" else "sessions"
    return if (overview.sessionsThisWeek > 0) {
        "${overview.sessionsThisWeek} $sessionWord this week · ${overview.totalMinutesThisWeek} min"
    } else {
        val totalWord = if (overview.totalSessions == 1) "session" else "sessions"
        "${overview.totalSessions} $totalWord logged"
    }
}

package com.badmintontracker.shared.exercises

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/**
 * Local-first persistence for completed drill sessions, backed by [Settings].
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
        persist(updated)
        return entry
    }

    /** Deletes one logged session by [logId]. */
    fun deleteSession(logId: String) {
        val updated = logsState.value.filterNot { it.id == logId }
        if (updated.size != logsState.value.size) {
            persist(updated)
        }
    }

    private fun persist(list: List<DrillSessionLog>) {
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

    private companion object {
        const val KEY_DRILL_LOGS = "drill_session_logs_v1"
    }
}

/** Filters logs to [playerId] when provided, or returns all logs when [playerId] is null. */
fun filterDrillLogs(logs: List<DrillSessionLog>, playerId: String?): List<DrillSessionLog> =
    if (playerId == null) logs else logs.filter { it.playerId == playerId }

/** Computes badge stats for [exerciseId] from [logs] relative to [nowEpochMs]. */
fun progressForExercise(
    logs: List<DrillSessionLog>,
    exerciseId: String,
    nowEpochMs: Long,
): ExerciseProgressSummary {
    val matching = logs.filter { it.exerciseId == exerciseId }
    if (matching.isEmpty()) {
        return ExerciseProgressSummary(
            totalSessions = 0,
            completedToday = false,
            lastCompletedAtEpochMs = null,
            lastEffort = null,
        )
    }
    val latest = matching.maxBy { it.completedAtEpochMs }
    val todayBucket = nowEpochMs / MILLIS_PER_DAY
    val latestBucket = latest.completedAtEpochMs / MILLIS_PER_DAY
    return ExerciseProgressSummary(
        totalSessions = matching.size,
        completedToday = latestBucket == todayBucket,
        lastCompletedAtEpochMs = latest.completedAtEpochMs,
        lastEffort = latest.effort,
    )
}

/** Computes weekly and all-time training totals from [logs] relative to [nowEpochMs]. */
fun trainingOverview(logs: List<DrillSessionLog>, nowEpochMs: Long): TrainingOverview {
    val cutoff = nowEpochMs - SEVEN_DAYS_MS
    val recent = logs.filter { it.completedAtEpochMs >= cutoff }
    val weeklySeconds = recent.sumOf { it.durationSeconds }
    val weeklyMinutes = if (weeklySeconds <= 0) 0 else max(1, (weeklySeconds / 60.0).roundToInt())
    return TrainingOverview(
        sessionsThisWeek = recent.size,
        totalMinutesThisWeek = weeklyMinutes,
        totalSessions = logs.size,
        uniqueDrillsTried = logs.map { it.exerciseId }.distinct().size,
    )
}

/** Short completion pill text shown on an exercise card, or null if never logged. */
fun exerciseProgressBadge(
    summary: ExerciseProgressSummary,
    nowEpochMs: Long,
): String? {
    if (summary.totalSessions <= 0) return null
    if (summary.completedToday) {
        return if (summary.totalSessions == 1) "Done today" else "Done today · ${summary.totalSessions}x"
    }
    val lastMs = summary.lastCompletedAtEpochMs ?: return "Done ${summary.totalSessions}x"
    val ago = relativeDaysLabel(lastMs, nowEpochMs)
    return "Done ${summary.totalSessions}x · $ago"
}

/** Relative day label such as "Today", "Yesterday", or "3d ago". */
fun relativeDaysLabel(timestampEpochMs: Long, nowEpochMs: Long): String {
    val diffDays = ((nowEpochMs / MILLIS_PER_DAY) - (timestampEpochMs / MILLIS_PER_DAY)).coerceAtLeast(0L)
    return when (diffDays) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> "${diffDays}d ago"
    }
}

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

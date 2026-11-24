package com.badmintontracker.shared.exercises

import com.badmintontracker.shared.repo.selectAllPages
import com.badmintontracker.shared.util.SyncLock
import com.badmintontracker.shared.util.randomUuid
import com.badmintontracker.shared.util.withLock
import com.russhwolf.settings.Settings
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.math.roundToInt

/** One completed drill practice session stored offline-first and synced via Supabase. */
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
    /** User who recorded the session (coach or linked player). Null for legacy offline rows. */
    val loggedByUserId: String? = null,
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

private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

private fun DrillEffort.toWire(): String = when (this) {
    DrillEffort.EASY -> "easy"
    DrillEffort.SOLID -> "solid"
    DrillEffort.HARD -> "hard"
}

private fun drillEffortFromWire(raw: String): DrillEffort = when (raw.lowercase()) {
    "easy" -> DrillEffort.EASY
    "hard" -> DrillEffort.HARD
    else -> DrillEffort.SOLID
}

/**
 * Local-first, account-scoped persistence and Supabase cloud sync for completed
 * drill sessions and per-drill custom plans.
 *
 * When constructed without a [SupabaseClient] (unit tests), persists locally
 * under the legacy keys with no network calls. When constructed with a client
 * (`RallyApp`), scopes local caches per signed-in user ID (one-time migrating
 * pre-upgrade legacy logs to the first signed-in user) and syncs player-linked
 * sessions through `public.drill_session_logs`.
 */
class DrillProgressRepository internal constructor(
    private val client: SupabaseClient?,
    private val settings: Settings,
    private val nowEpochMs: () -> Long,
    private val ownerId: (() -> String?)?,
) {
    /** Local-only constructor for offline tests. */
    constructor(settings: Settings, nowEpochMs: () -> Long) :
        this(client = null, settings = settings, nowEpochMs = nowEpochMs, ownerId = null)

    /** Cloud-synced, account-scoped constructor used by [com.badmintontracker.shared.RallyApp]. */
    constructor(client: SupabaseClient, settings: Settings, nowEpochMs: () -> Long) :
        this(client = client, settings = settings, nowEpochMs = nowEpochMs, ownerId = { client.auth.currentUserOrNull()?.id })

    @Serializable
    private data class StoredDrillLog(val log: DrillSessionLog, val dirty: Boolean)

    @Serializable
    private data class AccountDrillState(
        val logs: List<StoredDrillLog> = emptyList(),
        @SerialName("pending_deletes") val pendingDeletes: List<String> = emptyList(),
        @SerialName("saved_plans") val savedPlans: Map<String, DrillPlan> = emptyMap(),
    )

    @Serializable
    private data class RemoteDrillRow(
        val id: String,
        @SerialName("player_id") val playerId: String,
        @SerialName("logged_by_user_id") val loggedByUserId: String? = null,
        @SerialName("exercise_id") val exerciseId: String,
        @SerialName("match_id") val matchId: String? = null,
        @SerialName("completed_at") val completedAt: Instant,
        @SerialName("sets_completed") val setsCompleted: Int,
        @SerialName("sets_target") val setsTarget: Int,
        @SerialName("duration_seconds") val durationSeconds: Int,
        val effort: String,
        val note: String = "",
    )

    @Serializable
    private data class UpsertDrillRow(
        val id: String,
        @SerialName("player_id") val playerId: String,
        @SerialName("exercise_id") val exerciseId: String,
        @SerialName("match_id") val matchId: String?,
        @SerialName("completed_at") val completedAt: Instant,
        @SerialName("sets_completed") val setsCompleted: Int,
        @SerialName("sets_target") val setsTarget: Int,
        @SerialName("duration_seconds") val durationSeconds: Int,
        val effort: String,
        val note: String,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = SyncLock()
    private val syncMutex = Mutex()

    private var loadedScope: String? = currentScope()
    private var storedLogs: List<StoredDrillLog>
    private var pendingDeletes: Set<String>
    private var savedPlans: Map<String, DrillPlan>

    init {
        val loaded = loadForScope(loadedScope)
        storedLogs = loaded.logs
        pendingDeletes = loaded.pendingDeletes.toSet()
        savedPlans = loaded.savedPlans
    }

    private val logsState = MutableStateFlow(storedLogs.map { it.log })
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
    ): DrillSessionLog = lock.withLock {
        ensureScopeCurrentLocked()
        val now = nowEpochMs()
        val entry = DrillSessionLog(
            id = randomUuid(),
            exerciseId = exerciseId,
            playerId = playerId,
            matchId = matchId,
            completedAtEpochMs = now,
            setsCompleted = setsCompleted.coerceAtLeast(0),
            setsTarget = setsTarget.coerceAtLeast(1),
            durationSeconds = durationSeconds.coerceAtLeast(0),
            effort = effort,
            note = note.trim(),
            loggedByUserId = ownerId?.invoke(),
        )
        val updated = (listOf(StoredDrillLog(entry, dirty = playerId != null)) + storedLogs)
            .sortedByDescending { it.log.completedAtEpochMs }
            .take(MAX_STORED_LOGS)
        persistLocked(updated, pendingDeletes, savedPlans)
        entry
    }

    /** Deletes one logged session by [logId]. Queues a server delete when cloud-backed. */
    fun deleteSession(logId: String) = lock.withLock {
        ensureScopeCurrentLocked()
        val updated = storedLogs.filterNot { it.log.id == logId }
        val nextDeletes = if (client != null && UUID_RE.matches(logId)) pendingDeletes + logId else pendingDeletes
        if (updated.size != storedLogs.size || nextDeletes != pendingDeletes) {
            persistLocked(updated, nextDeletes, savedPlans)
        }
    }

    /** Returns the previously customized [DrillPlan] for [exerciseId], or null if never customized. */
    fun savedPlanFor(exerciseId: String): DrillPlan? = lock.withLock {
        ensureScopeCurrentLocked()
        savedPlans[exerciseId]
    }

    /**
     * Returns the saved custom [DrillPlan] for [exercise] when compatible with
     * its work mode, falling back to [defaultPlanFor].
     */
    fun planForExercise(exercise: Exercise): DrillPlan {
        val defaultPlan = defaultPlanFor(exercise)
        val saved = savedPlanFor(exercise.id) ?: return defaultPlan
        return if (saved.mode == defaultPlan.mode) saved else defaultPlan
    }

    /** Persists a customized [DrillPlan] for [exerciseId] so future runs pre-fill it. */
    fun savePlan(exerciseId: String, plan: DrillPlan) = lock.withLock {
        ensureScopeCurrentLocked()
        if (savedPlans[exerciseId] == plan) return@withLock
        val next = LinkedHashMap<String, DrillPlan>(savedPlans.size + 1)
        next.putAll(savedPlans)
        next[exerciseId] = plan
        while (next.size > MAX_SAVED_PLANS) {
            val oldestKey = next.keys.firstOrNull() ?: break
            next.remove(oldestKey)
        }
        persistLocked(storedLogs, pendingDeletes, next)
    }

    /** Reloads per-account local state after sign-in, sign-out, or account switch. */
    fun reloadForCurrentOwner() = lock.withLock {
        val nextScope = currentScope()
        if (nextScope == loadedScope) return@withLock
        adoptScopeLocked(nextScope)
    }

    /**
     * Offline-first two-way sync with `public.drill_session_logs`:
     * deletes queued removals, upserts dirty player-linked sessions, and merges
     * all visible sessions for both coach and linked player.
     */
    suspend fun sync(): Result<Unit> = syncMutex.withLock {
        reloadForCurrentOwner()
        val postgrest = client?.postgrest ?: return Result.success(Unit)
        val activeOwner = ownerId?.invoke() ?: return Result.failure(IllegalStateException("Not signed in."))
        runCatching {
            val deletes = lock.withLock { pendingDeletes.toList() }
            if (deletes.isNotEmpty()) {
                postgrest.from(TABLE).delete { filter { isIn("id", deletes) } }
                lock.withLock {
                    persistLocked(storedLogs, pendingDeletes - deletes.toSet(), savedPlans)
                }
            }

            val dirtyRows = lock.withLock {
                storedLogs.filter { it.dirty && it.log.playerId != null && UUID_RE.matches(it.log.playerId) }
                    .map { it.log }
            }
            if (dirtyRows.isNotEmpty()) {
                val payload = dirtyRows.map { log ->
                    UpsertDrillRow(
                        id = log.id,
                        playerId = log.playerId!!,
                        exerciseId = log.exerciseId,
                        matchId = log.matchId?.takeIf { UUID_RE.matches(it) },
                        completedAt = Instant.fromEpochMilliseconds(log.completedAtEpochMs),
                        setsCompleted = log.setsCompleted,
                        setsTarget = log.setsTarget,
                        durationSeconds = log.durationSeconds,
                        effort = log.effort.toWire(),
                        note = log.note.take(500),
                    )
                }
                postgrest.from(TABLE).upsert(payload)
            }

            val remote = postgrest.from(TABLE).selectAllPages<RemoteDrillRow> {
                order("completed_at", Order.DESCENDING)
                order("id", Order.ASCENDING)
            }

            lock.withLock {
                if (ownerId?.invoke() != activeOwner) return@withLock
                val pushedIds = dirtyRows.map { it.id }.toSet()
                val remoteLogs = remote
                    .filterNot { it.id in pendingDeletes }
                    .map { row ->
                        StoredDrillLog(
                            log = DrillSessionLog(
                                id = row.id,
                                exerciseId = row.exerciseId,
                                playerId = row.playerId,
                                matchId = row.matchId,
                                completedAtEpochMs = row.completedAt.toEpochMilliseconds(),
                                setsCompleted = row.setsCompleted,
                                setsTarget = row.setsTarget,
                                durationSeconds = row.durationSeconds,
                                effort = drillEffortFromWire(row.effort),
                                note = row.note,
                                loggedByUserId = row.loggedByUserId,
                            ),
                            dirty = false,
                        )
                    }
                val remoteIds = remoteLogs.map { it.log.id }.toSet()
                val keptLocal = storedLogs.filter { item ->
                    val id = item.log.id
                    id !in remoteIds && id !in pendingDeletes &&
                        (item.log.playerId == null || !UUID_RE.matches(item.log.playerId) || (item.dirty && id !in pushedIds))
                }
                val merged = (remoteLogs + keptLocal)
                    .sortedByDescending { it.log.completedAtEpochMs }
                    .take(MAX_STORED_LOGS)
                persistLocked(merged, pendingDeletes, savedPlans)
            }
        }
    }

    private fun currentScope(): String? = if (ownerId == null) LOCAL_SCOPE else ownerId.invoke()

    private fun ensureScopeCurrentLocked() {
        val scope = currentScope()
        if (scope != loadedScope) adoptScopeLocked(scope)
    }

    private fun adoptScopeLocked(scope: String?) {
        loadedScope = scope
        val loaded = loadForScope(scope)
        storedLogs = loaded.logs
        pendingDeletes = loaded.pendingDeletes.toSet()
        savedPlans = loaded.savedPlans
        logsState.value = storedLogs.map { it.log }
    }

    private fun persistLocked(
        nextLogs: List<StoredDrillLog>,
        nextDeletes: Set<String>,
        nextPlans: Map<String, DrillPlan>,
    ) {
        storedLogs = nextLogs
        pendingDeletes = nextDeletes
        savedPlans = nextPlans
        logsState.value = nextLogs.map { it.log }
        val scope = loadedScope ?: return
        if (scope == LOCAL_SCOPE) {
            settings.putString(KEY_LEGACY_LOGS, json.encodeToString(nextLogs.map { it.log }))
            settings.putString(KEY_LEGACY_PLANS, json.encodeToString(nextPlans))
        } else {
            val payload = AccountDrillState(
                logs = nextLogs,
                pendingDeletes = nextDeletes.toList(),
                savedPlans = nextPlans,
            )
            settings.putString(accountKey(scope), json.encodeToString(AccountDrillState.serializer(), payload))
        }
    }

    private fun loadForScope(scope: String?): AccountDrillState {
        if (scope == null) return AccountDrillState()
        if (scope == LOCAL_SCOPE) {
            return AccountDrillState(
                logs = loadLegacyLogs().map { StoredDrillLog(it, dirty = false) },
                savedPlans = loadLegacyPlans(),
            )
        }
        val scopedRaw = settings.getStringOrNull(accountKey(scope))
        if (scopedRaw != null) {
            val decoded = runCatching { json.decodeFromString(AccountDrillState.serializer(), scopedRaw) }.getOrNull()
            if (decoded != null) {
                return decoded.copy(logs = decoded.logs.sortedByDescending { it.log.completedAtEpochMs })
            }
        }
        // One-time migration of pre-upgrade un-scoped logs/plans to the first signed-in user.
        if (settings.getStringOrNull(KEY_LEGACY_MIGRATED) == null) {
            val legacyLogs = loadLegacyLogs()
            val legacyPlans = loadLegacyPlans()
            if (legacyLogs.isNotEmpty() || legacyPlans.isNotEmpty()) {
                val migratedLogs = legacyLogs.map { log ->
                    val syncable = log.playerId != null && UUID_RE.matches(log.playerId)
                    val migratedId = if (syncable && !UUID_RE.matches(log.id)) randomUuid() else log.id
                    StoredDrillLog(
                        log = log.copy(id = migratedId, loggedByUserId = log.loggedByUserId ?: scope),
                        dirty = syncable,
                    )
                }.sortedByDescending { it.log.completedAtEpochMs }
                val migrated = AccountDrillState(logs = migratedLogs, savedPlans = legacyPlans)
                settings.putString(accountKey(scope), json.encodeToString(AccountDrillState.serializer(), migrated))
                settings.putString(KEY_LEGACY_MIGRATED, scope)
                settings.remove(KEY_LEGACY_LOGS)
                settings.remove(KEY_LEGACY_PLANS)
                return migrated
            }
            settings.putString(KEY_LEGACY_MIGRATED, scope)
        }
        return AccountDrillState()
    }

    private fun loadLegacyLogs(): List<DrillSessionLog> {
        val raw = settings.getStringOrNull(KEY_LEGACY_LOGS) ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<DrillSessionLog>>(raw)
                .sortedByDescending { it.completedAtEpochMs }
        }.getOrDefault(emptyList())
    }

    private fun loadLegacyPlans(): Map<String, DrillPlan> {
        val raw = settings.getStringOrNull(KEY_LEGACY_PLANS) ?: return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, DrillPlan>>(raw)
        }.getOrDefault(emptyMap())
    }

    private fun accountKey(owner: String): String = "$KEY_ACCOUNT_PREFIX$owner"

    private companion object {
        const val TABLE = "drill_session_logs"
        const val LOCAL_SCOPE = "__local__"
        const val KEY_LEGACY_LOGS = "drill_session_logs_v1"
        const val KEY_LEGACY_PLANS = "drill_custom_plans_v1"
        const val KEY_LEGACY_MIGRATED = "drill_progress_legacy_migrated_v1"
        const val KEY_ACCOUNT_PREFIX = "drill_progress_v2:"
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

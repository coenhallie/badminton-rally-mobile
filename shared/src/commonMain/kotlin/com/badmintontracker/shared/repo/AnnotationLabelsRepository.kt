package com.badmintontracker.shared.repo

import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.model.LabelColor
import com.badmintontracker.shared.model.LabelUsage
import com.russhwolf.settings.Settings
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface AnnotationLabelsRepository {
    /** Last known list, creation order. Survives a cold start offline. */
    val labels: StateFlow<List<AnnotationLabel>>

    /**
     * The subset the courtside board may tag a rally with, and the subset the
     * clip and local-video note pickers offer.
     *
     * Derived here rather than exported as a predicate for each screen to apply:
     * six surfaces want a subset, and six copies of the same filter is six
     * chances for two of them to disagree about what "on the board" means.
     */
    val scoreboardLabels: StateFlow<List<AnnotationLabel>>
    val clipLabels: StateFlow<List<AnnotationLabel>>

    suspend fun refresh(): Result<Unit>
    /** [color] null picks a swatch automatically. */
    suspend fun create(name: String, color: LabelColor?, usage: LabelUsage): Result<AnnotationLabel>
    suspend fun rename(id: String, name: String): Result<Unit>
    suspend fun recolor(id: String, color: LabelColor): Result<Unit>
    suspend fun setUsage(id: String, usage: LabelUsage): Result<Unit>
    suspend fun delete(id: String): Result<Unit>
}

class AnnotationLabelsRepositoryImpl internal constructor(
    private val client: SupabaseClient,
    private val settings: Settings,
    private val awaitAuthReady: suspend () -> Unit,
) : AnnotationLabelsRepository {

    /**
     * The only constructor visible outside this module - and the only one the
     * exported Swift/ObjC surface sees, unchanged from before this seam existed.
     * It always resolves [awaitAuthReady] to the real, unmockable
     * client.auth.awaitInitialization(). The class header's internal
     * constructor is the test seam: it lets a test control that wait directly
     * instead of racing supabase-kt's real asynchronous session restore.
     */
    constructor(client: SupabaseClient, settings: Settings) :
        this(client, settings, { client.auth.awaitInitialization() })

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A background scope this repository owns for three jobs: [cacheReconciliation]
     * below, plus the eager sharing coroutines behind [scoreboardLabels] and
     * [clipLabels]. It is not threaded in from RallyApp because nothing in this
     * codebase's app graph yet owns an app-lifetime scope with a teardown hook,
     * and this repository is a singleton that lives as long as the process - a
     * coroutine with no natural end is not a leak on an object with that
     * lifetime. Introducing a shared app-scope contract (who cancels it, when)
     * for this one caller would be speculative infrastructure the rest of the
     * graph does not need yet.
     *
     * [cacheReconciliation] runs once and completes; the two sharing coroutines
     * never do - [SharingStarted.Eagerly] keeps each subscribed to [state] for
     * as long as this object exists, which is the whole point of them (see
     * [scoreboardLabels]'s comment). All three are fine to leave running forever
     * for the same reason: nothing here is holding a resource that needs
     * releasing, and the object's own lifetime is "forever" already.
     *
     * Declared above [state] because [scoreboardLabels] and [clipLabels] need it
     * to eagerly start their own derivation: property initializers run in
     * declaration order, so a [scope] declared below them would still be
     * uninitialized when their `stateIn` call reads it.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val state = MutableStateFlow(loadCache())
    override val labels: StateFlow<List<AnnotationLabel>> = state.asStateFlow()

    /**
     * Eagerly started on the scope this class already owns, for the same reason
     * the board's own state is eager: the value has to be correct the instant a
     * screen reads it, and there is nothing to defer - it is a filter over a
     * list already in memory. See [scope]'s comment for why a coroutine that
     * never completes is acceptable on this object.
     *
     * Shared on `scope + Dispatchers.Unconfined`, not bare [scope] (which is
     * [Dispatchers.Default], a real thread pool): [SharingStarted.Eagerly]
     * only guarantees the sharing coroutine is *launched* during property
     * init, not that it has run. On a dispatcher that has to hop onto a pool
     * thread, `state.value = next` in [publish] would be visible here only
     * after that hop is scheduled - an async window a synchronous `.value`
     * read right after a mutation could lose. Unconfined runs the collector
     * inline on whichever thread resumes it, so the initial subscription
     * happens synchronously during construction (reading [state]'s current
     * value, which is already initialized above), and every later `publish`
     * pushes straight through the `map` filter on the calling thread before
     * that call returns - which is what makes the "correct the instant a
     * screen reads it" guarantee actually true, not just true at startup.
     */
    override val scoreboardLabels: StateFlow<List<AnnotationLabel>> =
        state.map { all -> all.filter { it.scope.onScoreboard } }
            .stateIn(
                scope + Dispatchers.Unconfined,
                SharingStarted.Eagerly,
                state.value.filter { it.scope.onScoreboard },
            )

    override val clipLabels: StateFlow<List<AnnotationLabel>> =
        state.map { all -> all.filter { it.scope.onClips } }
            .stateIn(
                scope + Dispatchers.Unconfined,
                SharingStarted.Eagerly,
                state.value.filter { it.scope.onClips },
            )

    /**
     * supabase-kt restores a persisted session asynchronously: right after a
     * fresh client is constructed, client.auth.currentUserOrNull() is null even
     * when a session is on disk, and only settles once initialization finishes.
     * [loadCache] runs synchronously in the constructor above, so on a real cold
     * start it sees no owner and (correctly, fail-safe) publishes an empty list.
     * That is fine for correctness but defeats the cache's purpose: the offline
     * local-video label picker would be empty even though the data is sitting
     * right there in Settings. This waits for the owner to actually be known,
     * then re-reads the cache under that owner and publishes it - through the
     * exact same [loadCache] owner check, so a still-unmatched or still-unknown
     * owner still yields nothing. compareAndSet only overwrites an untouched
     * (still-empty) state, so a refresh() that already landed real data while
     * this was waiting is never clobbered by a stale cache read.
     */
    internal val cacheReconciliation: Job = scope.launch {
        runCatching {
            awaitAuthReady()
            val reconciled = loadCache()
            if (reconciled.isNotEmpty()) state.compareAndSet(emptyList(), reconciled)
        }
    }

    @Serializable private data class NewLabelRow(
        val name: String,
        @SerialName("color_key") val colorKey: String,
        val usage: String,
    )
    @Serializable private data class NamePatch(val name: String)
    @Serializable private data class ColorPatch(@SerialName("color_key") val colorKey: String)
    @Serializable private data class UsagePatch(val usage: String)

    /**
     * The cache envelope, scoped to whoever was signed in when it was written.
     * Settings has no per-user namespacing of its own, and nothing guarantees a
     * sign-out hook runs before the next launch (a session can simply expire, or
     * the app can be killed) - so the owner id travels inside the payload and
     * [loadCache] refuses to hand back a list stamped with someone else's id.
     */
    @Serializable private data class CachedLabels(
        @SerialName("owner_id") val ownerId: String?,
        val labels: List<AnnotationLabel>,
    )

    override suspend fun refresh(): Result<Unit> = runCatching {
        val rows = client.postgrest.from(TABLE)
            .select { order("created_at", Order.ASCENDING) }
            .decodeList<AnnotationLabel>()
        publish(rows)
    }

    override suspend fun create(
        name: String,
        color: LabelColor?,
        usage: LabelUsage,
    ): Result<AnnotationLabel> {
        val trimmed = name.trim()
        validate(trimmed)?.let { return Result.failure(it) }
        val swatch = color ?: nextUnusedColor(state.value.map { it.colorKey })
        return runCatching {
            val row = client.postgrest.from(TABLE)
                .insert(NewLabelRow(trimmed, swatch.key, usage.key)) { select() }
                .decodeSingle<AnnotationLabel>()
            publish(state.value + row)
            row
        }.recoverCatching { throw it.asDuplicateName(trimmed) }
    }

    override suspend fun rename(id: String, name: String): Result<Unit> {
        val trimmed = name.trim()
        validate(trimmed, ignoringId = id)?.let { return Result.failure(it) }
        return runCatching {
            client.postgrest.from(TABLE).update(NamePatch(trimmed)) { filter { eq("id", id) } }
            publish(state.value.map { if (it.id == id) it.copy(name = trimmed) else it })
        }.recoverCatching { throw it.asDuplicateName(trimmed) }
    }

    override suspend fun recolor(id: String, color: LabelColor): Result<Unit> = runCatching {
        client.postgrest.from(TABLE).update(ColorPatch(color.key)) { filter { eq("id", id) } }
        publish(state.value.map { if (it.id == id) it.copy(colorKey = color.key) else it })
    }

    override suspend fun setUsage(id: String, usage: LabelUsage): Result<Unit> = runCatching {
        client.postgrest.from(TABLE).update(UsagePatch(usage.key)) { filter { eq("id", id) } }
        publish(state.value.map { if (it.id == id) it.copy(usage = usage.key) else it })
    }

    override suspend fun delete(id: String): Result<Unit> = runCatching {
        client.postgrest.from(TABLE).delete { filter { eq("id", id) } }
        publish(state.value.filterNot { it.id == id })
    }

    /**
     * The unique index is the real authority, and [validate] can only see the
     * in-memory list: on a cold start offline, or before the first refresh lands,
     * that list is empty and the duplicate check passes. So the server's rejection
     * is a normal path, not an edge case, and it gets the same sentence rather
     * than surfacing as "HTTP 409".
     */
    private fun Throwable.asDuplicateName(name: String): Throwable {
        val text = message.orEmpty()
        val duplicate = "23505" in text ||
            "duplicate key" in text ||
            "annotation_labels_owner_name_key" in text
        return if (duplicate) duplicateNameError(name) else this
    }

    /**
     * Mirrors the DB's own rules so the user sees the problem before a round trip,
     * and so the message is ours rather than a Postgres constraint name.
     */
    private fun validate(trimmed: String, ignoringId: String? = null): Throwable? = when {
        trimmed.isEmpty() -> IllegalArgumentException("Give the label a name.")
        trimmed.length > MAX_NAME -> IllegalArgumentException("The name can be up to $MAX_NAME characters.")
        state.value.any { it.id != ignoringId && it.name.equals(trimmed, ignoreCase = true) } ->
            duplicateNameError(trimmed)
        else -> null
    }

    /**
     * The one place the duplicate-name sentence is spelled out, so the in-memory
     * check ([validate]) and the server-rejection mapping ([asDuplicateName]) can
     * never quietly drift apart into two different messages for the same failure.
     */
    private fun duplicateNameError(name: String): Throwable =
        IllegalArgumentException("You already have a label called \"$name\".")

    private fun publish(next: List<AnnotationLabel>) {
        state.value = next
        val envelope = CachedLabels(currentOwnerId(), next)
        settings.putString(KEY_CACHE, json.encodeToString(CachedLabels.serializer(), envelope))
    }

    /**
     * Only hands back a cached list when it was written by whoever is signed in
     * right now. A missing owner on either side - a legacy cache written before
     * this scoping existed, or nobody currently signed in - is treated the same
     * as a mismatch: better an empty picker than one user's label names leaking
     * into another user's app.
     */
    private fun loadCache(): List<AnnotationLabel> {
        val cached = settings.getStringOrNull(KEY_CACHE)
            ?.let { runCatching { json.decodeFromString(CachedLabels.serializer(), it) }.getOrNull() }
            ?: return emptyList()
        val owner = currentOwnerId()
        return if (owner != null && owner == cached.ownerId) cached.labels else emptyList()
    }

    private fun currentOwnerId(): String? = client.auth.currentUserOrNull()?.id

    companion object {
        private const val TABLE = "annotation_labels"
        private const val KEY_CACHE = "annotation_labels_cache"
        private const val MAX_NAME = 24

        /**
         * First swatch not already in use. Past ten labels every swatch is taken,
         * so it wraps by count rather than returning null: the inline picker path
         * always passes a null colour and has no way to handle running out.
         */
        fun nextUnusedColor(takenKeys: List<String>): LabelColor =
            LabelColor.PALETTE.firstOrNull { it.key !in takenKeys }
                ?: LabelColor.PALETTE[takenKeys.size % LabelColor.PALETTE.size]
    }
}

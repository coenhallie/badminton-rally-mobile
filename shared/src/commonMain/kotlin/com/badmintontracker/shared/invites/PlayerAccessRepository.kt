package com.badmintontracker.shared.invites

import com.russhwolf.settings.Settings
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface PlayerAccessRepository {
    /** Coach view: invite/link status per owned player ID. */
    val accessByPlayerId: StateFlow<Map<String, PlayerAccessState>>

    /** Player view (and dual-role coaches): coach profiles linked to the signed-in account. */
    val linkedProfiles: StateFlow<List<LinkedPlayerProfile>>

    /** True once the current account's linked profiles have loaded from cache or server. */
    val linkedProfilesLoaded: StateFlow<Boolean>

    suspend fun refreshAccessStates(): Result<Unit>
    suspend fun createInvite(playerId: String): Result<CreatedInvite>
    suspend fun revokeInvite(playerId: String): Result<Unit>
    suspend fun unlinkPlayer(playerId: String): Result<Unit>

    suspend fun refreshLinkedProfiles(): Result<Unit>
    suspend fun previewInvite(code: String): Result<InvitePreview>
    suspend fun redeemInviteNewAccount(code: String, email: String, password: String): Result<RedeemedInvite>
    suspend fun redeemInviteExistingAccount(code: String): Result<RedeemedInvite>

    fun clear()
}

class PlayerAccessRepositoryImpl internal constructor(
    private val client: SupabaseClient,
    private val settings: Settings,
    private val awaitAuthReady: suspend () -> Unit,
) : PlayerAccessRepository {

    constructor(client: SupabaseClient, settings: Settings) :
        this(client, settings, { client.auth.awaitInitialization() })

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val accessState = MutableStateFlow(loadAccessCache(currentUserId()))
    override val accessByPlayerId: StateFlow<Map<String, PlayerAccessState>> = accessState.asStateFlow()

    private val initialProfiles = loadProfilesCache(currentUserId())
    private val profilesState = MutableStateFlow(initialProfiles)
    override val linkedProfiles: StateFlow<List<LinkedPlayerProfile>> = profilesState.asStateFlow()

    private val loadedState = MutableStateFlow(initialProfiles.isNotEmpty())
    override val linkedProfilesLoaded: StateFlow<Boolean> = loadedState.asStateFlow()

    internal val cacheReconciliation: Job = scope.launch {
        runCatching {
            awaitAuthReady()
            val owner = currentUserId()
            val cachedAccess = loadAccessCache(owner)
            if (cachedAccess.isNotEmpty()) accessState.compareAndSet(emptyMap(), cachedAccess)
            val cachedProfiles = loadProfilesCache(owner)
            if (cachedProfiles.isNotEmpty()) {
                if (profilesState.compareAndSet(emptyList(), cachedProfiles)) {
                    loadedState.value = true
                }
            }
        }
    }

    internal val ownerFollower: Job = scope.launch {
        var lastOwner: String? = currentUserId()
        client.auth.sessionStatus.collect { status ->
            when (status) {
                is SessionStatus.Authenticated -> {
                    val next = status.session.user?.id
                    if (next != lastOwner) {
                        lastOwner = next
                        accessState.value = loadAccessCache(next)
                        val cached = loadProfilesCache(next)
                        profilesState.value = cached
                        loadedState.value = cached.isNotEmpty()
                    }
                }
                is SessionStatus.NotAuthenticated -> {
                    if (lastOwner != null) {
                        lastOwner = null
                        clear()
                    }
                }
                is SessionStatus.Initializing, is SessionStatus.RefreshFailure -> Unit
            }
        }
    }

    @Serializable private data class PlayerIdArg(@SerialName("p_player_id") val playerId: String)
    @Serializable private data class CodeArg(@SerialName("p_code") val code: String)
    // No defaults on action: supabase-kt's Json omits properties equal to their default.
    @Serializable private data class PreviewBody(val action: String, val code: String)
    @Serializable private data class RedeemBody(
        val action: String,
        val code: String,
        val email: String,
        val password: String,
    )
    @Serializable private data class ErrorBody(val error: String? = null)
    @Serializable private data class AuthRedeemRow(
        @SerialName("player_id") val playerId: String,
        @SerialName("player_name") val playerName: String,
        @SerialName("coach_email") val coachEmail: String = "",
    )
    @Serializable private data class CachedAccess(
        @SerialName("owner_id") val ownerId: String?,
        val states: List<PlayerAccessState>,
    )
    @Serializable private data class CachedProfiles(
        @SerialName("owner_id") val ownerId: String?,
        val profiles: List<LinkedPlayerProfile>,
    )

    override suspend fun refreshAccessStates(): Result<Unit> = runCatching {
        val rows = client.postgrest.rpc("list_player_access_states").decodeList<PlayerAccessState>()
        publishAccess(rows.associateBy { it.playerId })
    }.recoverCatching { throw it.toInviteException() }

    override suspend fun createInvite(playerId: String): Result<CreatedInvite> = runCatching {
        val rows = client.postgrest.rpc("create_player_invite", PlayerIdArg(playerId))
            .decodeList<CreatedInvite>()
        val created = rows.firstOrNull() ?: throw InviteException(InviteErrorKind.UNKNOWN)
        val updated = accessState.value + (
            playerId to PlayerAccessState(
                playerId = playerId,
                status = PlayerAccessStatus.PENDING,
                pendingCode = created.code,
                pendingExpiresAt = created.expiresAt,
            )
        )
        publishAccess(updated)
        created
    }.recoverCatching { throw it.toInviteException() }

    override suspend fun revokeInvite(playerId: String): Result<Unit> = runCatching {
        client.postgrest.rpc("revoke_player_invite", PlayerIdArg(playerId))
        val updated = accessState.value + (
            playerId to PlayerAccessState(playerId = playerId, status = PlayerAccessStatus.UNINVITED)
        )
        publishAccess(updated)
    }.recoverCatching { throw it.toInviteException() }

    override suspend fun unlinkPlayer(playerId: String): Result<Unit> = runCatching {
        client.postgrest.rpc("unlink_player_login", PlayerIdArg(playerId))
        val updated = accessState.value + (
            playerId to PlayerAccessState(playerId = playerId, status = PlayerAccessStatus.UNINVITED)
        )
        publishAccess(updated)
    }.recoverCatching { throw it.toInviteException() }

    override suspend fun refreshLinkedProfiles(): Result<Unit> = runCatching {
        val rows = client.postgrest.rpc("list_my_player_profiles").decodeList<LinkedPlayerProfile>()
        publishProfiles(rows)
        loadedState.value = true
    }.recoverCatching { throw it.toInviteException() }

    override suspend fun previewInvite(code: String): Result<InvitePreview> = runCatching {
        val normalized = normalizeInviteCode(code)
            ?: throw InviteException(InviteErrorKind.INVALID_OR_EXPIRED)
        val response = client.functions(
            function = EDGE_FUNCTION,
            body = PreviewBody(action = "preview", code = normalized),
            headers = jsonHeaders(),
        )
        val bodyText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw parseEdgeError(bodyText, response.status.value)
        }
        json.decodeFromString(InvitePreview.serializer(), bodyText)
    }.recoverCatching { throw it.toInviteException() }

    override suspend fun redeemInviteNewAccount(
        code: String,
        email: String,
        password: String,
    ): Result<RedeemedInvite> = runCatching {
        val normalized = normalizeInviteCode(code)
            ?: throw InviteException(InviteErrorKind.INVALID_OR_EXPIRED)
        val trimmedEmail = email.trim().lowercase()
        if (!EMAIL_REGEX.matches(trimmedEmail)) {
            throw InviteException(InviteErrorKind.INVALID_EMAIL)
        }
        if (password.length < MIN_PASSWORD_LENGTH) {
            throw InviteException(InviteErrorKind.WEAK_PASSWORD)
        }
        val response = client.functions(
            function = EDGE_FUNCTION,
            body = RedeemBody(action = "redeem", code = normalized, email = trimmedEmail, password = password),
            headers = jsonHeaders(),
        )
        val bodyText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw parseEdgeError(bodyText, response.status.value)
        }
        val redeemed = json.decodeFromString(RedeemedInvite.serializer(), bodyText)
        client.auth.signInWith(Email) {
            this.email = trimmedEmail
            this.password = password
        }
        runCatching { refreshLinkedProfiles() }
        redeemed
    }.recoverCatching { throw it.toInviteException() }

    override suspend fun redeemInviteExistingAccount(code: String): Result<RedeemedInvite> = runCatching {
        val normalized = normalizeInviteCode(code)
            ?: throw InviteException(InviteErrorKind.INVALID_OR_EXPIRED)
        val rows = client.postgrest.rpc("redeem_player_invite_authenticated", CodeArg(normalized))
            .decodeList<AuthRedeemRow>()
        val row = rows.firstOrNull() ?: throw InviteException(InviteErrorKind.INVALID_OR_EXPIRED)
        runCatching { refreshLinkedProfiles() }
        RedeemedInvite(playerId = row.playerId, playerName = row.playerName, coachEmail = row.coachEmail)
    }.recoverCatching { throw it.toInviteException() }

    override fun clear() {
        accessState.value = emptyMap()
        profilesState.value = emptyList()
        loadedState.value = false
    }

    private fun jsonHeaders(): Headers = Headers.build {
        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    }

    private fun publishAccess(next: Map<String, PlayerAccessState>) {
        val owner = currentUserId()
        accessState.value = next
        if (owner == null) return
        val envelope = CachedAccess(owner, next.values.toList())
        settings.putString(KEY_ACCESS_CACHE, json.encodeToString(CachedAccess.serializer(), envelope))
    }

    private fun publishProfiles(next: List<LinkedPlayerProfile>) {
        val owner = currentUserId()
        profilesState.value = next
        if (owner == null) return
        val envelope = CachedProfiles(owner, next)
        settings.putString(KEY_PROFILES_CACHE, json.encodeToString(CachedProfiles.serializer(), envelope))
    }

    private fun loadAccessCache(owner: String?): Map<String, PlayerAccessState> {
        if (owner == null) return emptyMap()
        val cached = settings.getStringOrNull(KEY_ACCESS_CACHE)
            ?.let { runCatching { json.decodeFromString(CachedAccess.serializer(), it) }.getOrNull() }
            ?: return emptyMap()
        return if (cached.ownerId == owner) cached.states.associateBy { it.playerId } else emptyMap()
    }

    private fun loadProfilesCache(owner: String?): List<LinkedPlayerProfile> {
        if (owner == null) return emptyList()
        val cached = settings.getStringOrNull(KEY_PROFILES_CACHE)
            ?.let { runCatching { json.decodeFromString(CachedProfiles.serializer(), it) }.getOrNull() }
            ?: return emptyList()
        return if (cached.ownerId == owner) cached.profiles else emptyList()
    }

    private fun currentUserId(): String? = client.auth.currentUserOrNull()?.id

    private fun parseEdgeError(bodyText: String, statusCode: Int): InviteException {
        val code = runCatching { json.decodeFromString(ErrorBody.serializer(), bodyText).error }
            .getOrNull()
        return mapErrorCode(code, statusCode, bodyText)
    }

    private fun Throwable.toInviteException(): InviteException {
        if (this is InviteException) return this
        if (this is HttpRequestException) return InviteException(InviteErrorKind.NETWORK, cause = this)
        if (this is RestException) {
            val raw = "${this.error} ${this.description.orEmpty()} ${this.message.orEmpty()}"
            val parsedCode = runCatching { json.decodeFromString(ErrorBody.serializer(), this.error).error }
                .getOrNull()
            return mapErrorCode(parsedCode, this.statusCode, raw, this)
        }
        val msg = message.orEmpty()
        return mapErrorCode(null, null, msg, this)
    }

    private fun mapErrorCode(
        code: String?,
        statusCode: Int?,
        raw: String,
        cause: Throwable? = null,
    ): InviteException {
        val lower = "${code.orEmpty()} $raw".lowercase()
        val kind = when {
            statusCode == 429 || lower.contains("rate_limited") -> InviteErrorKind.RATE_LIMITED
            lower.contains("email_exists") || lower.contains("already been registered") -> InviteErrorKind.EMAIL_EXISTS
            lower.contains("already_linked") || lower.contains("p0005") -> InviteErrorKind.ALREADY_LINKED
            lower.contains("cannot_invite_self") || lower.contains("p0004") -> InviteErrorKind.CANNOT_INVITE_SELF
            lower.contains("invalid_or_expired") || lower.contains("p0006") -> InviteErrorKind.INVALID_OR_EXPIRED
            lower.contains("weak_password") -> InviteErrorKind.WEAK_PASSWORD
            lower.contains("invalid_email") -> InviteErrorKind.INVALID_EMAIL
            lower.contains("not_owner") || lower.contains("p0002") -> InviteErrorKind.NOT_OWNER
            else -> InviteErrorKind.UNKNOWN
        }
        return InviteException(kind, cause = cause)
    }

    private companion object {
        const val EDGE_FUNCTION = "redeem-player-invite"
        const val KEY_ACCESS_CACHE = "player_access_cache_v1"
        const val KEY_PROFILES_CACHE = "linked_player_profiles_cache_v1"
        const val MIN_PASSWORD_LENGTH = 8
        val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}

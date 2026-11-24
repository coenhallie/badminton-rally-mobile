package com.badmintontracker.shared.repo

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

interface AuthRepository {
    val sessionFlow: Flow<SessionStatus>
    fun currentUserId(): String?
    /** The signed-in account's email, for Settings. Null while signed out or if the session has none. */
    fun currentUserEmail(): String?
    /** Whether the signed-in session is a coach or an invited player account. */
    fun accountRole(): AccountRole = AccountRole.COACH
    suspend fun signInEmail(email: String, password: String): Result<Unit>
    suspend fun signOut(): Result<Unit>
}

class AuthRepositoryImpl(private val client: SupabaseClient) : AuthRepository {

    override val sessionFlow: Flow<SessionStatus> = client.auth.sessionStatus

    override fun currentUserId(): String? = client.auth.currentUserOrNull()?.id

    override fun currentUserEmail(): String? = client.auth.currentUserOrNull()?.email

    override fun accountRole(): AccountRole {
        val role = client.auth.currentUserOrNull()
            ?.appMetadata
            ?.get("account_role")
            ?.jsonPrimitive
            ?.contentOrNull
        return if (role == "player") AccountRole.PLAYER else AccountRole.COACH
    }

    override suspend fun signInEmail(email: String, password: String): Result<Unit> =
        runCatching {
            client.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
        }

    override suspend fun signOut(): Result<Unit> = runCatching {
        client.auth.signOut()
    }
}

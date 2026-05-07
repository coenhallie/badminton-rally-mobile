package com.badmintontracker.shared.repo

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    val sessionFlow: Flow<SessionStatus>
    fun currentUserId(): String?
    suspend fun signInEmail(email: String, password: String): Result<Unit>
    suspend fun signInWithGoogle(): Result<Unit>
    suspend fun signOut(): Result<Unit>
}

class AuthRepositoryImpl(private val client: SupabaseClient) : AuthRepository {

    override val sessionFlow: Flow<SessionStatus> = client.auth.sessionStatus

    override fun currentUserId(): String? = client.auth.currentUserOrNull()?.id

    override suspend fun signInEmail(email: String, password: String): Result<Unit> =
        runCatching {
            client.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
        }

    override suspend fun signInWithGoogle(): Result<Unit> = runCatching {
        client.auth.signInWith(Google)
    }

    override suspend fun signOut(): Result<Unit> = runCatching {
        client.auth.signOut()
    }
}

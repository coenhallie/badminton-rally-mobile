package com.badmintontracker.android.testing

import com.badmintontracker.shared.repo.AuthRepository
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.MutableStateFlow

class FakeAuthRepository : AuthRepository {
    val session = MutableStateFlow<SessionStatus>(SessionStatus.NotAuthenticated(false))
    override val sessionFlow = session
    var currentUserIdValue: String? = "user-self"
    var nextEmailResult: Result<Unit> = Result.success(Unit)
    val emailCalls = mutableListOf<Pair<String, String>>()
    val signOutCalls = mutableListOf<Unit>()

    override fun currentUserId(): String? = currentUserIdValue

    override suspend fun signInEmail(email: String, password: String): Result<Unit> {
        emailCalls += email to password
        return nextEmailResult
    }
    override suspend fun signOut(): Result<Unit> {
        signOutCalls += Unit
        return Result.success(Unit)
    }
}

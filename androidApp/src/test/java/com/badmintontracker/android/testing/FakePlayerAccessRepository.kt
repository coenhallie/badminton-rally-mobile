package com.badmintontracker.android.testing

import com.badmintontracker.shared.invites.CreatedInvite
import com.badmintontracker.shared.invites.InviteErrorKind
import com.badmintontracker.shared.invites.InviteException
import com.badmintontracker.shared.invites.InvitePreview
import com.badmintontracker.shared.invites.LinkedPlayerProfile
import com.badmintontracker.shared.invites.PlayerAccessRepository
import com.badmintontracker.shared.invites.PlayerAccessState
import com.badmintontracker.shared.invites.PlayerAccessStatus
import com.badmintontracker.shared.invites.RedeemedInvite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant

class FakePlayerAccessRepository(
    initialAccess: Map<String, PlayerAccessState> = emptyMap(),
    initialLinked: List<LinkedPlayerProfile> = emptyList(),
    initialLoaded: Boolean = false,
) : PlayerAccessRepository {

    override val accessByPlayerId = MutableStateFlow(initialAccess)
    override val linkedProfiles = MutableStateFlow(initialLinked)
    override val linkedProfilesLoaded = MutableStateFlow(initialLoaded)

    var nextPreviewResult: Result<InvitePreview> =
        Result.failure(InviteException(InviteErrorKind.INVALID_OR_EXPIRED))
    var nextCreateInviteResult: Result<CreatedInvite> = Result.success(
        CreatedInvite("inv-1", "K7M4QP9R", Instant.parse("2026-12-01T00:00:00Z")),
    )
    var nextRedeemNewResult: Result<RedeemedInvite> = Result.success(
        RedeemedInvite("p-1", "Marco", "coach@example.com"),
    )
    var nextRedeemExistingResult: Result<RedeemedInvite> = Result.success(
        RedeemedInvite("p-1", "Marco", "coach@example.com"),
    )
    var nextRevokeResult: Result<Unit> = Result.success(Unit)
    var nextUnlinkResult: Result<Unit> = Result.success(Unit)
    var nextRefreshAccessResult: Result<Unit> = Result.success(Unit)
    var nextRefreshLinkedResult: Result<Unit> = Result.success(Unit)

    val previewCalls = mutableListOf<String>()
    val redeemNewCalls = mutableListOf<Triple<String, String, String>>()
    val redeemExistingCalls = mutableListOf<String>()
    val createCalls = mutableListOf<String>()
    val revokeCalls = mutableListOf<String>()
    val unlinkCalls = mutableListOf<String>()
    var refreshAccessCount = 0
    var refreshLinkedCount = 0

    override suspend fun refreshAccessStates(): Result<Unit> {
        refreshAccessCount++
        return nextRefreshAccessResult
    }

    override suspend fun createInvite(playerId: String): Result<CreatedInvite> {
        createCalls += playerId
        return nextCreateInviteResult.onSuccess { created ->
            accessByPlayerId.value = accessByPlayerId.value + (
                playerId to PlayerAccessState(
                    playerId = playerId,
                    status = PlayerAccessStatus.PENDING,
                    pendingCode = created.code,
                    pendingExpiresAt = created.expiresAt,
                )
            )
        }
    }

    override suspend fun revokeInvite(playerId: String): Result<Unit> {
        revokeCalls += playerId
        return nextRevokeResult.onSuccess {
            accessByPlayerId.value = accessByPlayerId.value + (
                playerId to PlayerAccessState(playerId = playerId, status = PlayerAccessStatus.UNINVITED)
            )
        }
    }

    override suspend fun unlinkPlayer(playerId: String): Result<Unit> {
        unlinkCalls += playerId
        return nextUnlinkResult.onSuccess {
            accessByPlayerId.value = accessByPlayerId.value + (
                playerId to PlayerAccessState(playerId = playerId, status = PlayerAccessStatus.UNINVITED)
            )
        }
    }

    override suspend fun refreshLinkedProfiles(): Result<Unit> {
        refreshLinkedCount++
        return nextRefreshLinkedResult.onSuccess {
            linkedProfilesLoaded.value = true
        }
    }

    override suspend fun previewInvite(code: String): Result<InvitePreview> {
        previewCalls += code
        return nextPreviewResult
    }

    override suspend fun redeemInviteNewAccount(
        code: String,
        email: String,
        password: String,
    ): Result<RedeemedInvite> {
        redeemNewCalls += Triple(code, email, password)
        return nextRedeemNewResult
    }

    override suspend fun redeemInviteExistingAccount(code: String): Result<RedeemedInvite> {
        redeemExistingCalls += code
        return nextRedeemExistingResult
    }

    override fun clear() {
        accessByPlayerId.value = emptyMap()
        linkedProfiles.value = emptyList()
        linkedProfilesLoaded.value = false
    }
}

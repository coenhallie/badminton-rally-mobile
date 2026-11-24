package com.badmintontracker.shared.invites

import com.badmintontracker.shared.prefs.RacketArm
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Coach-side invite / link status for one player profile. */
@Serializable
enum class PlayerAccessStatus {
    @SerialName("uninvited") UNINVITED,
    @SerialName("pending") PENDING,
    @SerialName("linked") LINKED,
}

/** One row returned by `list_player_access_states()`. */
@Serializable
data class PlayerAccessState(
    @SerialName("player_id") val playerId: String,
    val status: PlayerAccessStatus,
    @SerialName("linked_email") val linkedEmail: String? = null,
    @SerialName("linked_at") val linkedAt: Instant? = null,
    @SerialName("pending_code") val pendingCode: String? = null,
    @SerialName("pending_expires_at") val pendingExpiresAt: Instant? = null,
)

/** Returned by `create_player_invite(p_player_id)`. */
@Serializable
data class CreatedInvite(
    @SerialName("invite_id") val inviteId: String,
    val code: String,
    @SerialName("expires_at") val expiresAt: Instant,
)

/** Returned by the `redeem-player-invite` Edge Function `preview` action. */
@Serializable
data class InvitePreview(
    val playerName: String,
    val racketArm: RacketArm? = null,
    val coachEmail: String,
)

/** Returned by `redeem-player-invite` `redeem` or `redeem_player_invite_authenticated`. */
@Serializable
data class RedeemedInvite(
    val playerId: String,
    val playerName: String,
    val coachEmail: String,
)

/** One coach-owned profile linked to the signed-in player via `player_logins`. */
@Serializable
data class LinkedPlayerProfile(
    @SerialName("player_id") val playerId: String,
    @SerialName("coach_id") val coachId: String,
    @SerialName("coach_email") val coachEmail: String,
    val name: String,
    @SerialName("racket_arm") val racketArm: RacketArm? = null,
    @SerialName("linked_at") val linkedAt: Instant,
)

/** Typed failure categories for invite creation, preview, and redemption. */
enum class InviteErrorKind {
    INVALID_OR_EXPIRED,
    ALREADY_LINKED,
    EMAIL_EXISTS,
    WEAK_PASSWORD,
    INVALID_EMAIL,
    CANNOT_INVITE_SELF,
    RATE_LIMITED,
    NOT_OWNER,
    NETWORK,
    UNKNOWN,
}

class InviteException(
    val kind: InviteErrorKind,
    override val message: String = PlayerInviteCopy.errorMessage(kind),
    cause: Throwable? = null,
) : Exception(message, cause)

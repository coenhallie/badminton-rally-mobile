package com.badmintontracker.shared.invites

import com.badmintontracker.shared.matchlist.MONTHS
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.ceil

/**
 * Shared copy for coach invites, player onboarding, focused player home, and
 * unlinked recovery so Android and iOS speak identically.
 */
object PlayerInviteCopy {
    // Sign-in additions
    const val SIGN_IN_INVITE_DIVIDER = "Invited by a coach?"
    const val SIGN_IN_HAVE_CODE = "I have an invite code"
    const val SIGN_IN_FOOTNOTE_INVITE_ONLY = "Accounts are invite-only. Ask your coach for a code."

    // Coach Player access card (Profile tab)
    const val ACCESS_SECTION_TITLE = "Player access"
    const val ACCESS_BADGE_CONNECTED = "Connected"
    const val ACCESS_BADGE_PENDING = "Invite pending"
    const val ACCESS_SHOW_CODE = "Show / share code"
    const val ACCESS_CANCEL_INVITE = "Cancel invite"
    const val ACCESS_REMOVE = "Remove access"
    const val ACCESS_REMOVE_CONFIRM = "Remove"
    const val ACCESS_CANCEL = "Cancel"

    fun inviteCardHeading(playerName: String): String = "Invite $playerName to Shuttl"
    fun inviteCardBody(playerName: String): String =
        "Share a one-time code so $playerName can view their matches, analysis, and log drills."
    fun inviteButton(playerName: String): String = "Invite $playerName"
    fun removeAccessTitle(playerName: String): String = "Remove $playerName's access?"
    fun removeAccessBody(playerName: String): String =
        "$playerName will no longer see this profile on their phone. Their profile, matches, and drill history stay in your account."

    // Coach Invite bottom sheet
    fun inviteSheetTitle(playerName: String): String = "Invite $playerName"
    fun inviteSheetSubtitle(playerName: String): String =
        "Have $playerName open Shuttl and enter or scan this code."
    const val TAP_TO_COPY = "Tap to copy"
    const val CODE_COPIED = "Invite code copied."
    const val SHARE_INVITE = "Share invite"
    const val GENERATE_NEW_CODE = "Generate new code"
    const val SHARE_SUBJECT = "Your Shuttl invite"

    fun shareInviteText(playerName: String, code: String): String {
        val formatted = formatInviteCode(code)
        return "Hi $playerName, join me on Shuttl to view your matches, analysis, and drills.\n\n" +
            "Open Shuttl, tap \"I have an invite code\", and enter:\n$formatted"
    }

    fun expiryCaption(expiresAt: Instant, nowEpochMs: Long): String {
        val remainingMs = expiresAt.toEpochMilliseconds() - nowEpochMs
        if (remainingMs <= 0L) return "Expired · generate a new code"
        val days = ceil(remainingMs / 86_400_000.0).toInt().coerceAtLeast(1)
        val unit = if (days == 1) "day" else "days"
        return "Single-use · expires in $days $unit"
    }

    fun linkedDateLabel(linkedAt: Instant, timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
        val d = linkedAt.toLocalDateTime(timeZone)
        return "Linked ${MONTHS[d.monthNumber - 1]} ${d.dayOfMonth}"
    }

    fun phoneOnlyMatchesNote(playerName: String, count: Int): String =
        if (count == 1) {
            "1 phone-only match won't appear on $playerName's phone until uploaded to the cloud."
        } else {
            "$count phone-only matches won't appear on $playerName's phone until uploaded to the cloud."
        }

    // Player Join Invite flow
    const val JOIN_TITLE = "Join your coach"
    const val JOIN_CODE_LABEL = "Invite code"
    const val JOIN_CODE_PLACEHOLDER = "XXXX-XXXX"
    /** Under the code boxes while one holds a character no code contains. */
    const val JOIN_CODE_CHAR_HINT = "Codes never use 0, O, 1, I or L."
    const val JOIN_SCAN_QR = "Scan QR"
    const val JOIN_VERIFY = "Continue"
    const val JOIN_VERIFYING = "Checking code…"
    const val JOIN_VERIFIED_BADGE = "Verified"
    const val JOIN_EMAIL_LABEL = "Your email"
    const val JOIN_PASSWORD_LABEL = "Choose a password (8+ chars)"
    const val JOIN_SIGN_IN_PASSWORD_LABEL = "Password"
    const val JOIN_CREATE_SUBMIT = "Create account & continue"
    const val JOIN_CREATING = "Creating account…"
    const val JOIN_LINK_SUBMIT = "Sign in & link profile"
    const val JOIN_LINKING = "Linking profile…"
    const val JOIN_SWITCH_TO_SIGN_IN = "Already have an account? Sign in to link"
    const val JOIN_SWITCH_TO_CREATE = "New to Shuttl? Create an account instead"
    const val JOIN_QR_UNAVAILABLE = "QR scanning isn't available on this device. Enter the code manually."
    const val JOIN_QR_INVALID = "That QR code isn't a Shuttl invite."

    fun invitedBy(coachEmail: String): String =
        if (coachEmail.isBlank()) "Invited by your coach" else "Invited by $coachEmail"

    // Focused Player Home & Settings
    const val MY_PROFILE_SECTION = "My profile"
    const val SWITCH_COACH_PROFILE = "Switch coach profile"
    const val JOIN_ANOTHER_COACH = "Join another coach"
    const val JOIN_ANOTHER_COACH_DETAIL = "Enter an invite code from a coach"
    const val JOIN_ANOTHER_SUBMIT = "Link profile"
    const val UNLINKED_TITLE = "No active coach profile"
    const val UNLINKED_BODY = "Ask your coach for an invite code to view your matches, analysis, and drills."
    const val UNLINKED_CTA = "Enter invite code"
    const val ANALYSIS_DOWNLOADING = "Downloading analysis…"
    const val ANALYSIS_DOWNLOAD_FAILED = "Couldn't download analysis. Check your connection and try again."
    const val ANALYSIS_RETRY = "Retry"

    fun coachedBy(coachEmail: String): String =
        if (coachEmail.isBlank()) "Coached profile" else "Coach · $coachEmail"

    fun linkedSuccessToast(playerName: String): String = "Connected to $playerName."

    fun errorMessage(kind: InviteErrorKind): String = when (kind) {
        InviteErrorKind.INVALID_OR_EXPIRED -> "That invite code is invalid or expired. Ask your coach for a new one."
        InviteErrorKind.ALREADY_LINKED -> "This player profile is already linked to an account."
        InviteErrorKind.EMAIL_EXISTS -> "An account with this email already exists. Sign in to link your profile."
        InviteErrorKind.WEAK_PASSWORD -> "Use a password with at least 8 characters."
        InviteErrorKind.INVALID_EMAIL -> "Enter a valid email address."
        InviteErrorKind.CANNOT_INVITE_SELF -> "You can't redeem your own coach invite."
        InviteErrorKind.RATE_LIMITED -> "Too many attempts. Wait a few minutes and try again."
        InviteErrorKind.NOT_OWNER -> "Only the coach who owns this player can manage invites."
        InviteErrorKind.NETWORK -> "Couldn't reach Shuttl. Check your connection and try again."
        InviteErrorKind.UNKNOWN -> "Something went wrong. Please try again."
    }
}

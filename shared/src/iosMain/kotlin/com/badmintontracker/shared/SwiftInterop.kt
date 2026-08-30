package com.badmintontracker.shared

import com.badmintontracker.shared.repo.userFacingMessage
import com.badmintontracker.shared.auth.friendlyAuthError
import com.badmintontracker.shared.model.AnnotationLabel
import com.badmintontracker.shared.model.LabelColor
import com.badmintontracker.shared.model.LabelUsage
import com.badmintontracker.shared.model.MatchMetadata
import com.badmintontracker.shared.model.MatchShare
import com.badmintontracker.shared.model.RallyAnnotation
import com.badmintontracker.shared.repo.AnnotationLabelsRepository
import com.badmintontracker.shared.repo.AnnotationsRepository
import com.badmintontracker.shared.repo.AuthRepository
import com.badmintontracker.shared.repo.ShareError
import com.badmintontracker.shared.repo.SharesRepository
import com.badmintontracker.shared.repo.VideosRepository
import com.badmintontracker.shared.repo.userMessage
import com.badmintontracker.shared.scoring.ScoreLogsRepository
import com.russhwolf.settings.NSUserDefaultsSettings
import kotlinx.datetime.Instant
import platform.Foundation.NSUserDefaults

// kotlin.Result does not cross the ObjC bridge usefully; these wrappers return
// null on success and a ready-to-display message on failure.

suspend fun AuthRepository.signInEmailOrMessage(email: String, password: String): String? =
    signInEmail(email, password).exceptionOrNull()?.let(::friendlyAuthError)

suspend fun AuthRepository.signOutOrMessage(): String? =
    signOut().exceptionOrNull()?.let { it.userFacingMessage("Sign-out failed.") }

suspend fun SharesRepository.shareOrMessage(videoId: String, email: String): String? =
    share(videoId, email).exceptionOrNull()?.let { (it as? ShareError).userMessage() }

suspend fun SharesRepository.unshareOrMessage(videoId: String, userId: String): String? =
    unshare(videoId, userId).exceptionOrNull()?.let { "Couldn't remove access — please try again." }

suspend fun SharesRepository.listSharesOrNull(videoId: String): List<MatchShare>? =
    listShares(videoId).getOrNull()

/** Soft-failing read: nil means "leave whatever titles are already on screen". */
suspend fun VideosRepository.listMatchMetadataOrNull(): List<MatchMetadata>? =
    listMatchMetadata().getOrNull()

/** Soft-failing read: nil means "show no summary", never an error banner. */
suspend fun AnnotationsRepository.listForClipsOrNull(clipIds: List<String>): List<RallyAnnotation>? =
    listForClips(clipIds).getOrNull()

suspend fun VideosRepository.deleteMatchOrMessage(videoId: String): String? =
    deleteMatch(videoId).exceptionOrNull()?.let { "Couldn't delete the match. Please try again." }

suspend fun SharesRepository.leaveShareOrMessage(videoId: String): String? =
    leaveShare(videoId).exceptionOrNull()?.let { "Couldn't remove the shared match. Please try again." }

class AddAnnotationOutcome(val annotation: RallyAnnotation?, val errorMessage: String?)

suspend fun AnnotationsRepository.addAnnotationForSwift(
    clipId: String,
    timestampSeconds: Float,
    body: String,
    label: AnnotationLabel?,
): AddAnnotationOutcome = add(clipId, timestampSeconds, body, label).fold(
    onSuccess = { AddAnnotationOutcome(it, null) },
    onFailure = { AddAnnotationOutcome(null, it.userFacingMessage("Couldn't add note")) },
)

suspend fun AnnotationsRepository.deleteAnnotationOrMessage(id: String): String? =
    delete(id).exceptionOrNull()?.let { it.userFacingMessage("Couldn't delete note") }

class CreateLabelOutcome(val label: AnnotationLabel?, val errorMessage: String?)

/**
 * [color] null picks a swatch automatically. Both parameters are explicit
 * rather than defaulted because Kotlin default arguments do not cross the ObjC
 * boundary - Swift has to name them either way.
 */
suspend fun AnnotationLabelsRepository.createLabelForSwift(
    name: String,
    color: LabelColor?,
    usage: LabelUsage,
): CreateLabelOutcome =
    create(name, color, usage).fold(
        onSuccess = { CreateLabelOutcome(it, null) },
        onFailure = { CreateLabelOutcome(null, it.userFacingMessage("Couldn't add label")) },
    )

suspend fun AnnotationLabelsRepository.setLabelUsageOrMessage(id: String, usage: LabelUsage): String? =
    setUsage(id, usage).exceptionOrNull()?.let { it.userFacingMessage("Couldn't change where this label is used") }

suspend fun AnnotationLabelsRepository.renameLabelOrMessage(id: String, name: String): String? =
    rename(id, name).exceptionOrNull()?.let { it.userFacingMessage("Couldn't rename label") }

suspend fun AnnotationLabelsRepository.recolorLabelOrMessage(id: String, color: LabelColor): String? =
    recolor(id, color).exceptionOrNull()?.let { it.userFacingMessage("Couldn't change colour") }

suspend fun AnnotationLabelsRepository.deleteLabelOrMessage(id: String): String? =
    delete(id).exceptionOrNull()?.let { it.userFacingMessage("Couldn't delete label") }

suspend fun AnnotationLabelsRepository.refreshLabelsOrMessage(): String? =
    refresh().exceptionOrNull()?.let { it.userFacingMessage("Couldn't load labels") }

/**
 * Soft-failing: nil means the sync landed, a string is ready to display. The
 * message says what actually happened rather than "please try again", because the
 * matches are already safe on this phone and there is nothing to retry by hand.
 */
suspend fun ScoreLogsRepository.syncScoreLogsOrMessage(): String? =
    sync().exceptionOrNull()?.let { "Couldn't sync your matches. They're saved on this phone." }

/**
 * [hasVideo] picks the wording: a bound match's delete is two things at once
 * (the score log, then its video and clips), and this only ever runs the first
 * half, so a failure here leaves the second half - the video and its clips -
 * completely untouched, not merely undeleted on the server. Saying only "it's
 * gone from this phone" is true of the match and silent about that, which reads
 * as the whole gesture having landed. With the score_logs migration currently
 * unapplied on the server this is not a rare failure: it is the only outcome a
 * bound match's delete has today.
 */
suspend fun ScoreLogsRepository.deleteScoreMatchOrMessage(id: String, hasVideo: Boolean = false): String? =
    delete(id).exceptionOrNull()?.let { scoreLogDeleteFailedMessage(hasVideo) }

fun scoreLogDeleteFailedMessage(hasVideo: Boolean): String = if (hasVideo) {
    "Couldn't delete the match everywhere. The match is gone from this phone, but its video and clips are still there."
} else {
    "Couldn't delete the match everywhere. It's gone from this phone."
}

/**
 * A match store the iOS test bundle can build.
 *
 * `ScoreLogsRepository`'s local-only constructor needs a `Settings`, and Swift has
 * no way to make one, so `ScoringModelTests` cannot mirror androidApp's tests
 * without this. It is scoped to its own defaults suite and cleared on every call,
 * so it never sees or touches what the app stores. Nothing in the app calls it:
 * `RallyApp` builds the real store.
 */
fun testScoreLogsRepository(now: Instant, ownerId: String?): ScoreLogsRepository {
    val settings = NSUserDefaultsSettings(NSUserDefaults(suiteName = TEST_SCORE_LOGS_SUITE))
    settings.clear()
    return ScoreLogsRepository(settings, { now }, { ownerId })
}

private const val TEST_SCORE_LOGS_SUITE = "com.badmintontracker.ios.tests.scorelogs"

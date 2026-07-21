package com.badmintontracker.shared

import com.badmintontracker.shared.repo.userFacingMessage
import com.badmintontracker.shared.auth.friendlyAuthError
import com.badmintontracker.shared.model.AnnotationKind
import com.badmintontracker.shared.model.MatchShare
import com.badmintontracker.shared.model.RallyAnnotation
import com.badmintontracker.shared.repo.AnnotationsRepository
import com.badmintontracker.shared.repo.AuthRepository
import com.badmintontracker.shared.repo.ShareError
import com.badmintontracker.shared.repo.SharesRepository
import com.badmintontracker.shared.repo.VideosRepository
import com.badmintontracker.shared.repo.userMessage

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

suspend fun VideosRepository.deleteMatchOrMessage(videoId: String): String? =
    deleteMatch(videoId).exceptionOrNull()?.let { "Couldn't delete the match. Please try again." }

suspend fun SharesRepository.leaveShareOrMessage(videoId: String): String? =
    leaveShare(videoId).exceptionOrNull()?.let { "Couldn't remove the shared match. Please try again." }

class AddAnnotationOutcome(val annotation: RallyAnnotation?, val errorMessage: String?)

suspend fun AnnotationsRepository.addAnnotationForSwift(
    clipId: String,
    timestampSeconds: Float,
    body: String,
    kind: AnnotationKind?,
): AddAnnotationOutcome = add(clipId, timestampSeconds, body, kind).fold(
    onSuccess = { AddAnnotationOutcome(it, null) },
    onFailure = { AddAnnotationOutcome(null, it.userFacingMessage("Couldn't add note")) },
)

suspend fun AnnotationsRepository.deleteAnnotationOrMessage(id: String): String? =
    delete(id).exceptionOrNull()?.let { it.userFacingMessage("Couldn't delete note") }

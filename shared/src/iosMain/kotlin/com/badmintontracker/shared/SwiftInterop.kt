package com.badmintontracker.shared

import com.badmintontracker.shared.auth.friendlyAuthError
import com.badmintontracker.shared.model.AnnotationKind
import com.badmintontracker.shared.model.MatchShare
import com.badmintontracker.shared.model.RallyAnnotation
import com.badmintontracker.shared.repo.AnnotationsRepository
import com.badmintontracker.shared.repo.AuthRepository
import com.badmintontracker.shared.repo.ShareError
import com.badmintontracker.shared.repo.SharesRepository
import com.badmintontracker.shared.repo.userMessage

// kotlin.Result does not cross the ObjC bridge usefully; these wrappers return
// null on success and a ready-to-display message on failure.

suspend fun AuthRepository.signInEmailOrMessage(email: String, password: String): String? =
    signInEmail(email, password).exceptionOrNull()?.let(::friendlyAuthError)

suspend fun AuthRepository.signOutOrMessage(): String? =
    signOut().exceptionOrNull()?.let { it.message ?: "Sign-out failed." }

suspend fun SharesRepository.shareOrMessage(videoId: String, email: String): String? =
    share(videoId, email).exceptionOrNull()?.let { (it as? ShareError).userMessage() }

suspend fun SharesRepository.unshareOrMessage(videoId: String, userId: String): String? =
    unshare(videoId, userId).exceptionOrNull()?.let { "Couldn't remove access — please try again." }

suspend fun SharesRepository.listSharesOrNull(videoId: String): List<MatchShare>? =
    listShares(videoId).getOrNull()

class AddAnnotationOutcome(val annotation: RallyAnnotation?, val errorMessage: String?)

suspend fun AnnotationsRepository.addAnnotationForSwift(
    clipId: String,
    timestampSeconds: Float,
    body: String,
    kind: AnnotationKind?,
): AddAnnotationOutcome = add(clipId, timestampSeconds, body, kind).fold(
    onSuccess = { AddAnnotationOutcome(it, null) },
    onFailure = { AddAnnotationOutcome(null, it.message ?: "Couldn't add annotation") },
)

suspend fun AnnotationsRepository.deleteAnnotationOrMessage(id: String): String? =
    delete(id).exceptionOrNull()?.let { it.message ?: "Couldn't delete annotation" }

package com.badmintontracker.shared.scoring

import com.badmintontracker.shared.localvideo.LocalAnnotationsRepository
import com.badmintontracker.shared.localvideo.LocalVideoEntry
import com.badmintontracker.shared.localvideo.LocalVideoRepository
import com.badmintontracker.shared.localvideo.canRemoveLocalVideo
import com.badmintontracker.shared.repo.ClipsRepository
import com.badmintontracker.shared.repo.VideosRepository

/**
 * Whether a match's video may be changed or removed.
 *
 * Two conditions, and neither is the failure the affordance was asked for. A
 * video that produced no rallies is the likeliest reason to want a different
 * one, but it is not the only one, and gating on it would also leave the second
 * dead end standing: a match bound to a video with no clips and no entry renders
 * [AttachKind.FINISHING_UP], whose only UI is a spinner. See the 2026-08-29
 * design, section 1.
 *
 * @param hasVideo the score log's video_id is set - a videos row exists.
 * @param entry the local video picked for this match, while it still exists. A
 *   video the coach picked but whose upload has not reached CREATE_ROW is still
 *   his to take back, so an entry alone is enough.
 */
fun canRemoveMatchVideo(hasVideo: Boolean, entry: LocalVideoEntry?): Boolean {
    if (!hasVideo && entry == null) return false
    // The same line the local library's own remove gesture draws, not a second
    // one: removing an entry mid-upload corrupts the run and swallows its
    // outcome, because the failure update targets an entry that is gone.
    return entry == null || canRemoveLocalVideo(entry.stage)
}

/** [canRemoveMatchVideo] for one match, from the stores every surface reads. */
fun scoreLogCanRemoveVideo(log: ScoreLog, entries: List<LocalVideoEntry>): Boolean =
    canRemoveMatchVideo(
        hasVideo = log.videoId != null,
        entry = entries.firstOrNull { it.scoreLogId == log.id },
    )

/** Which of the two gestures a prompt is for. */
enum class MatchVideoAction { REMOVE, CHANGE }

/** The confirm's wording. Built here so two platforms cannot word it differently. */
data class MatchVideoPrompt(val title: String, val body: String)

/**
 * What the confirm says, which has to be the opposite of the match list's own
 * bound-match confirm: that one deletes the points along with the video, and this
 * one deliberately keeps them. Reusing its wording would be actively wrong.
 *
 * @param hasServerVideo whether a videos row exists. When it does not - an entry
 *   that failed before CREATE_ROW - there are no rallies and no notes to lose,
 *   and saying there are would be a lie.
 */
fun matchVideoPrompt(action: MatchVideoAction, hasServerVideo: Boolean): MatchVideoPrompt {
    val whatGoes = if (hasServerVideo) {
        "The video, its rallies and any notes on them are deleted."
    } else {
        "The video is taken off this match."
    }
    val whatStays = "The match, its points and its tags stay."
    return when (action) {
        MatchVideoAction.REMOVE -> MatchVideoPrompt(
            title = "Remove this video?",
            body = "$whatGoes $whatStays",
        )
        MatchVideoAction.CHANGE -> MatchVideoPrompt(
            title = "Change this video?",
            body = "$whatGoes $whatStays You'll pick a new video and Shuttl will analyze it.",
        )
    }
}

/** Shown when the gesture lands while the pipeline is working on the entry. */
const val MATCH_VIDEO_BUSY_MESSAGE =
    "Shuttl is still working on this video. Wait for it to finish, then try again."

/** Shown when the server refused to delete the video. */
const val MATCH_VIDEO_REMOVE_FAILED_MESSAGE =
    "Couldn't remove the video. Please try again."

/**
 * Takes the video off a match and leaves the match: the other half of the match
 * list's `deleteBoundMatch`, which deletes both together.
 *
 * Returns null once the video is gone, or a ready-to-display message when it is
 * not - the `...OrMessage` idiom `SwiftInterop.kt` establishes, so iOS calls this
 * directly and Android reads the same string into its error channel.
 *
 * In shared rather than ported per platform, unlike the match list's own delete
 * sequences, because the ordering below has a failure branch that iOS cannot
 * test: `IosTestDoubles.kt` offers only a `NoopVideosRepository` whose
 * `deleteMatch` always succeeds and records nothing. See the 2026-08-29 design,
 * section 3.
 *
 * The ordering:
 *
 *  1. Refuse while the pipeline is running. Unreachable through either platform's
 *     UI, which honours [canRemoveMatchVideo] - this is the guard against the menu
 *     being opened before an upload starts and tapped after.
 *  2. The server, and only if it accepted: prune the clips from the local cache
 *     and unbind the score log. Local-first would leave the phone asserting
 *     something the server disagrees with, and would strand the entry, which is
 *     the only handle on a file that still has a videos row.
 *  3. The local entry and its local annotations, whether or not step 2 ran. An
 *     entry that never reached CREATE_ROW has no videos row, no clips and no
 *     binding, so removal is purely local for it.
 *
 * Deliberately does not refresh: [ClipsRepository.pruneVideo] and
 * [ScoreLogsRepository.detachVideo] both write through to flows the caller is
 * already collecting, and the unbind reaches the server on the next sync like
 * every other local-first mutation.
 */
suspend fun removeMatchVideoOrMessage(
    scoreLogId: String,
    scoreLogs: ScoreLogsRepository,
    videos: VideosRepository,
    clips: ClipsRepository,
    localVideos: LocalVideoRepository,
    localAnnotations: LocalAnnotationsRepository,
): String? {
    val log = scoreLogs.get(scoreLogId) ?: return MATCH_VIDEO_REMOVE_FAILED_MESSAGE
    val entry = localVideos.entries.value.firstOrNull { it.scoreLogId == scoreLogId }
    if (!canRemoveMatchVideo(hasVideo = log.videoId != null, entry = entry)) {
        // Nothing to remove reads as success: the caller asked for a match with no
        // video and that is what it has. Only a running pipeline is a refusal.
        return if (entry == null) null else MATCH_VIDEO_BUSY_MESSAGE
    }
    log.videoId?.let { videoId ->
        if (videos.deleteMatch(videoId).isFailure) return MATCH_VIDEO_REMOVE_FAILED_MESSAGE
        clips.pruneVideo(videoId)
        // The database does this too, through score_logs.video_id's ON DELETE SET
        // NULL and the unbind trigger, but the phone would not learn it until the
        // next sync. Idempotent against the trigger, which has already done it.
        scoreLogs.detachVideo(scoreLogId)
    }
    entry?.let {
        localVideos.remove(it.id)
        // Keyed by the entry id, which is also the videos id - see section 2 of
        // the 2026-08-28 design. The parameter is named videoId for the pipeline's
        // sake; the value to pass here is the entry's own id.
        localAnnotations.removeAllFor(it.id)
    }
    return null
}

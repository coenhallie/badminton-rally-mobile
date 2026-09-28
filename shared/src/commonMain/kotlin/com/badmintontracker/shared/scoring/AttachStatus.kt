package com.badmintontracker.shared.scoring

import com.badmintontracker.shared.localvideo.AnalyzeProgress
import com.badmintontracker.shared.localvideo.AnalyzeStage
import com.badmintontracker.shared.localvideo.LocalVideoEntry

/** Which pipeline state a match's video is in, for the surfaces that branch on it. */
enum class AttachKind { COURT_NOT_MARKED, UPLOADING, CLIPPING, FAILED, FINISHING_UP }

/**
 * What a match row says about the video being attached to it.
 *
 * [text] is built here rather than per platform for the same reason
 * [buildScoreMatchCard] is: two platforms writing the same sentence are two
 * chances to write it differently.
 */
data class AttachStatus(val text: String, val kind: AttachKind)

/**
 * Derives what to say, from the three signals a match row can see.
 *
 * The precedence is the point. After a successful run the pipeline removes the
 * entry (`runPipeline` ends in `localVideos.remove`) and drops its progress in a
 * `finally`, so "no entry" means one thing when clips have arrived and another
 * when they have not, and the two must not be confused. In particular the second
 * of those is *not* the "no rallies found" case: that is a FAILED entry carrying
 * the pipeline's own message, one branch above.
 *
 * @param hasVideo the score log's video_id is set - the binding reached the server.
 * @param entry the local video picked for this match, while it still exists.
 * @param uploadPercent the coordinator's transient upload progress, 0..100.
 * @param clipCount how many rally clips this match already has.
 */
fun attachStatus(
    hasVideo: Boolean,
    entry: LocalVideoEntry?,
    uploadPercent: Int?,
    clipCount: Int,
): AttachStatus? {
    if (entry != null) {
        when (entry.stage) {
            // LOCAL alone is not "unmarked": an on-device run never moves the
            // stage, so a video analyzed on this phone stays LOCAL for good. The
            // saved court points are what say the court was marked (both
            // platforms save them before any run starts), and past that the
            // device run speaks for itself, on the match list's row. Null, not a
            // fall-through: nothing is uploading for a LOCAL entry, so a bound
            // one with no clips is not "Finishing up…" either.
            AnalyzeStage.LOCAL -> return if (entry.keypoints == null) {
                AttachStatus("Video added, court not marked", AttachKind.COURT_NOT_MARKED)
            } else {
                null
            }
            AnalyzeStage.UPLOADING -> return AttachStatus(
                uploadPercent?.let { "Uploading $it%" } ?: "Uploading…",
                AttachKind.UPLOADING,
            )
            AnalyzeStage.PROCESSING -> return AttachStatus("Clipping…", AttachKind.CLIPPING)
            // The clips already exist by the time Phase 2 runs, so this row is
            // not waiting on the attachment any more. It reuses CLIPPING's kind
            // rather than gaining one: the attach banner's question is "can I
            // watch this match yet", and the answer during the pose pass is the
            // same yes-with-a-run-in-flight it is during clipping.
            AnalyzeStage.MEASURING -> return AttachStatus("Measuring movement…", AttachKind.CLIPPING)
            AnalyzeStage.FAILED ->
                return AttachStatus(entry.failureMessage ?: "Analysis failed", AttachKind.FAILED)
            // ANALYZED means the pipeline succeeded, not that clips have synced -
            // the same gap the no-entry path below exists to cover. This is
            // reachable only when the entry also carries local annotations (the
            // only reason the pipeline keeps an entry past success). An attached
            // entry once had no way to acquire any, because the local player
            // would not open it; the match page's Match section now opens any
            // entry on this phone, bound or not, so that chain of "you cannot
            // get there from here" no longer holds. Fall through to the same
            // clip-count question rather than assume clips have landed.
            AnalyzeStage.ANALYZED -> Unit
        }
    }
    // No entry and no binding: this match has never been given a video.
    if (!hasVideo) return null
    // Bound with clips on screen: the rally facet already says everything.
    if (clipCount > 0) return null
    return AttachStatus("Finishing up…", AttachKind.FINISHING_UP)
}

/**
 * [attachStatus] for one match, given the entry the caller has already picked
 * for it: the match list's builder, which folds a log, an entry and a video's
 * clips into one row, hands that same entry straight back here rather than
 * asking a second, independent lookup to agree with the first. A row and the
 * status it shows can then never disagree about which entry it is, because both
 * come from the one the row already chose. The match page shows that row's
 * status too, rather than deriving its own (see `showsStatusBanner`).
 */
fun attachStatusFor(
    log: ScoreLog,
    entry: LocalVideoEntry?,
    progress: Map<String, AnalyzeProgress>,
    clipCount: Int,
): AttachStatus? {
    val uploadPercent = entry
        ?.let { progress[it.id]?.uploadProgress }
        ?.let { (it * 100).toInt() }
    return attachStatus(
        hasVideo = log.videoId != null,
        entry = entry,
        uploadPercent = uploadPercent,
        clipCount = clipCount,
    )
}

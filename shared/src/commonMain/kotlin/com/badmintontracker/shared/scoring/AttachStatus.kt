package com.badmintontracker.shared.scoring

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
): AttachStatus? = when {
    entry != null -> when (entry.stage) {
        AnalyzeStage.LOCAL ->
            AttachStatus("Video added, court not marked", AttachKind.COURT_NOT_MARKED)
        AnalyzeStage.UPLOADING -> AttachStatus(
            uploadPercent?.let { "Uploading $it%" } ?: "Uploading…",
            AttachKind.UPLOADING,
        )
        AnalyzeStage.PROCESSING -> AttachStatus("Clipping…", AttachKind.CLIPPING)
        AnalyzeStage.FAILED ->
            AttachStatus(entry.failureMessage ?: "Analysis failed", AttachKind.FAILED)
        // Kept only because it carries local notes; nothing is in flight.
        AnalyzeStage.ANALYZED -> null
    }
    // No entry and no binding: this match has never been given a video.
    !hasVideo -> null
    // Bound with clips on screen: the rally facet already says everything.
    clipCount > 0 -> null
    else -> AttachStatus("Finishing up…", AttachKind.FINISHING_UP)
}

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
            AnalyzeStage.LOCAL ->
                return AttachStatus("Video added, court not marked", AttachKind.COURT_NOT_MARKED)
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
            // the same gap the no-entry path below exists to cover. Today this is
            // reachable only when the entry also carries local annotations (the
            // only reason the pipeline keeps an entry past success), and an
            // attached entry can no longer be opened in the local player to
            // acquire any - so in practice clipCount is already > 0 by the time
            // ANALYZED shows up here. That's correct by accident, not by
            // guarantee: it rests on a chain of "you cannot get there from here"
            // that later work can change. Fall through to the same clip-count
            // question rather than assume the accident holds.
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
 * [attachStatus] for one match, from the three sources every surface that shows
 * it needs to combine: the local entry picked for [log] (if it still exists),
 * the coordinator's transient upload progress keyed by entry id, and how many
 * clips this match's video already has.
 *
 * Pulled out so the match list's row (`ClipListViewModel.attachStatuses`,
 * `ClipListModel.attachMap()`) and the match page's own status
 * (`MatchViewModel`, `MatchModel`) derive the same answer for the same match -
 * three surfaces writing this by hand is three chances for them to disagree.
 */
fun scoreLogAttachStatus(
    log: ScoreLog,
    entries: List<LocalVideoEntry>,
    progress: Map<String, AnalyzeProgress>,
    clipCount: Int,
): AttachStatus? {
    val entry = entries.firstOrNull { it.scoreLogId == log.id }
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

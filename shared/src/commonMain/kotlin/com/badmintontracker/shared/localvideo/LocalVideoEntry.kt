package com.badmintontracker.shared.localvideo

import com.badmintontracker.shared.model.CourtKeypoints
import kotlinx.serialization.Serializable

enum class AnalyzeStage { LOCAL, UPLOADING, PROCESSING, FAILED, ANALYZED }

/** Pipeline steps in execution order; retry resumes from the failed one. */
enum class AnalyzeStep { UPLOAD, CREATE_ROW, KEYPOINTS, TRIGGER, PROCESSING }

/**
 * The pipeline is actively working on this entry. Drives the row spinner on
 * both platforms — settled stages (LOCAL, FAILED, ANALYZED) must not spin.
 */
fun isAnalysisRunning(stage: AnalyzeStage): Boolean =
    stage == AnalyzeStage.UPLOADING || stage == AnalyzeStage.PROCESSING

/**
 * Whether the row's remove affordances (swipe, menu) may be shown. Removing
 * deletes the entry — and on iOS the backing file — so while the pipeline is
 * uploading from that file or awaiting results, removal would corrupt the run
 * and swallow its outcome (the failure update targets an entry that no longer
 * exists). Both platforms must use this same rule.
 */
fun canRemoveLocalVideo(stage: AnalyzeStage): Boolean = !isAnalysisRunning(stage)

/**
 * Whether the row's "Edit details" affordance may be shown. Title and description
 * ride along on the videos INSERT and the database grants no UPDATE on either
 * column, so once CREATE_ROW has run the app could only show a name the database
 * does not have. Editing therefore stops the moment the entry leaves LOCAL — the
 * concrete divergence otherwise: CREATE_ROW inserts "A", TRIGGER fails, the user
 * renames to "B" and retries, and AnalyzeCoordinator.retry resumes at TRIGGER
 * without ever calling createVideo again. Both platforms must use this same rule.
 */
fun canEditLocalVideoDetails(stage: AnalyzeStage): Boolean = stage == AnalyzeStage.LOCAL

/**
 * Whether a failed run can be resumed from the step that failed instead of being
 * restarted from court marking. AnalyzeCoordinator.retry picks up at [LocalVideoEntry.failedStep],
 * and the keypoints are saved before the upload and survive a retry, so there is
 * nothing left to ask the user for.
 *
 * The keypoints check is not redundant with the stage, and must not be simplified
 * away on the argument that it is. Keypoints are written by startAnalysis before
 * the entry's first launchPipeline call, and fail() only ever runs from inside
 * runPipeline after that, so today a FAILED entry always already has them. This
 * guard is what stops a "Retry" button from lying if that ordering ever changes:
 * without it, retry would resume a run whose court is unknown.
 *
 * Note what this does NOT cover. A device run never moves the stage, so a row
 * showing "Retry" because its on-device analysis failed returns false here and
 * falls through to court marking, which is where a device re-run has to start -
 * LocalAnalysisRunner.start takes keypoints from the screen, not from the entry.
 * Both platforms must use this same rule.
 */
/**
 * Videos whose match no longer exists, and which should therefore go back to being
 * standalone local videos.
 *
 * An entry holds [LocalVideoEntry.scoreLogId] to say which match it was filmed for,
 * and every list filters "on this phone" down to entries with none. So an entry
 * pointing at a match that is gone appears NOWHERE: not under its match, which does
 * not exist, and not under local videos, which the filter excludes it from. It
 * cannot be opened, analysed, or removed. Measured on one real device: ten of
 * eleven videos were in this state.
 *
 * Deleting a match through the app already avoids this by removing the video with
 * it (see the Android and iOS delete paths, which say so). This is for every other
 * way a match can go: deleted on another device, or a log this account can no
 * longer see. Those never run that code.
 *
 * [knownScoreLogIds] MUST be authoritative, which in practice means straight out of
 * a successful sync. It is not enough for a log to be missing from whatever is
 * currently in memory: ScoreLogsRepository starts empty on a cold start, because
 * its cache is owner-scoped and supabase-kt has not restored the session yet, so
 * asking this question at render time would detach every attached video for the
 * length of that window and then reattach it.
 *
 * Detaching an entry mid-pipeline is deliberate rather than guarded against. The
 * run is keyed by entry id, so it is unaffected, and clearing the binding is what
 * stops AnalyzeCoordinator's onVideoRowReady from attaching the finished video to
 * a match that no longer exists.
 */
fun orphanedLocalVideoIds(
    entries: List<LocalVideoEntry>,
    knownScoreLogIds: Set<String>,
): List<String> = entries
    .filter { it.scoreLogId != null && it.scoreLogId !in knownScoreLogIds }
    .map { it.id }

fun canResumeFailedAnalysis(entry: LocalVideoEntry): Boolean =
    entry.stage == AnalyzeStage.FAILED && entry.keypoints != null

@Serializable
data class LocalVideoEntry(
    val id: String,              // client UUID; becomes videos.id on Analyze
    val uri: String,             // Android: content:// URI (persistable permission); iOS: Documents-relative path
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val addedAtEpochMs: Long,
    // User-supplied match metadata, written on the videos INSERT. The defaults are
    // load-bearing: a registry persisted before these fields existed must still
    // decode, or load() swallows the failure and returns an empty library.
    val title: String? = null,
    val description: String? = null,
    val keypoints: CourtKeypoints? = null,   // saved before upload; survives retry
    val stage: AnalyzeStage = AnalyzeStage.LOCAL,
    val failedStep: AnalyzeStep? = null,
    val failureMessage: String? = null,
    val resultSeen: Boolean = false,         // result dialog already shown for this failure
    /**
     * The match this video was picked for, or null for a video-first import.
     *
     * Held here rather than on the score log because score_logs.video_id has a
     * foreign key to videos(id), and no videos row exists until the pipeline's
     * CREATE_ROW step. Pushing the binding earlier would not fail one row: sync()
     * upserts every dirty row in one call, so it would stop every match on the
     * phone from syncing. See the 2026-08-28 design, section 3.1.
     *
     * Last in the parameter list on purpose: Swift constructs this type with every
     * argument spelled out, so appending is a one-line change there.
     */
    val scoreLogId: String? = null,
)

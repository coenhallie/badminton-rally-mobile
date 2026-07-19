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

@Serializable
data class LocalVideoEntry(
    val id: String,              // client UUID; becomes videos.id on Analyze
    val uri: String,             // Android: content:// URI (persistable permission); iOS: Documents-relative path
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val addedAtEpochMs: Long,
    val keypoints: CourtKeypoints? = null,   // saved before upload; survives retry
    val stage: AnalyzeStage = AnalyzeStage.LOCAL,
    val failedStep: AnalyzeStep? = null,
    val failureMessage: String? = null,
    val resultSeen: Boolean = false,         // result dialog already shown for this failure
)

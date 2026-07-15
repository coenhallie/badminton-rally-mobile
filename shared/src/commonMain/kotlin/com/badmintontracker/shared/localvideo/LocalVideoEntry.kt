package com.badmintontracker.shared.localvideo

import com.badmintontracker.shared.model.CourtKeypoints
import kotlinx.serialization.Serializable

enum class AnalyzeStage { LOCAL, UPLOADING, PROCESSING, FAILED, ANALYZED }

/** Pipeline steps in execution order; retry resumes from the failed one. */
enum class AnalyzeStep { UPLOAD, CREATE_ROW, KEYPOINTS, TRIGGER, PROCESSING }

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

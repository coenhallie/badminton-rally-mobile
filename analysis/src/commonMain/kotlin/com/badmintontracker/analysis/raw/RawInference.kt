package com.badmintontracker.analysis.raw

/**
 * Raw model output for one video, as the platform layer produces it.
 *
 * The boundary type of the design's section 5.1: the platform layer decodes,
 * runs models and writes this; :analysis reads it and computes everything
 * else. It never leaves the device, so it is binary rather than JSON - roughly
 * 5.5M floats for a 30-minute match, and a named-field encoding reproduces on
 * the phone the payload problem the cloud already has.
 *
 * Coordinates are in SOURCE-VIDEO pixels, already scaled back from model input
 * size the way production does at inference.py:154-155 with
 * w_scale = orig_w / 512 and h_scale = orig_h / 288. Carrying model-space
 * coordinates instead would push a scaling responsibility across the boundary
 * that belongs to the platform layer.
 */
data class RawHeader(
    val version: Int,
    val fps: Double,
    val totalFrames: Int,
    val videoWidth: Int,
    val videoHeight: Int,
    /** Stamps which weights produced this. Drives the clip re-anchoring rule. */
    val modelVersion: String,
)

data class RawShuttle(val x: Float, val y: Float, val confidence: Float, val visible: Boolean)

data class RawBox(
    val classId: Int,
    val confidence: Float,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
)

data class RawKeypoint(val x: Float, val y: Float, val confidence: Float)

data class RawPerson(val box: RawBox, val keypoints: List<RawKeypoint>)

/**
 * One frame of model output.
 *
 * A null [shuttle] means the model produced nothing for this frame; a
 * [RawShuttle] with `visible = false` means it produced output that resolved
 * to no acceptable blob. Those are different facts and the format keeps them
 * apart.
 *
 * [persons] is always empty in Stage 1, which is Phase 1 only - TrackNet and
 * the detector, no pose. The field exists so Stage 3 costs a count of zero
 * rather than a format version.
 */
data class RawFrame(
    val frame: Int,
    val timestamp: Double,
    val shuttle: RawShuttle?,
    val boxes: List<RawBox>,
    val persons: List<RawPerson>,
)

data class RawInference(val header: RawHeader, val frames: List<RawFrame>)

package com.badmintontracker.analysis.player

/**
 * Which COCO-17 keypoints are joined by a limb, for drawing.
 *
 * Here rather than in a platform view because both platforms draw the same
 * skeleton, and an edge list that disagrees between them produces a figure with
 * a limb attached to the wrong joint on one device only - the kind of defect
 * that survives review because each side looks self-consistent.
 */
object Skeleton {

    /** Limbs, as pairs of [Coco] indices. */
    val EDGES: List<Pair<Int, Int>> = listOf(
        // Head.
        0 to 1, 0 to 2, 1 to 3, 2 to 4,
        // Shoulders and arms.
        5 to 6, 5 to 7, 7 to 9, 6 to 8, 8 to 10,
        // Torso.
        5 to 11, 6 to 12, 11 to 12,
        // Legs.
        11 to 13, 13 to 15, 12 to 14, 14 to 16,
    )

    /**
     * The racket arm side is not known, so both arms are drawn alike.
     *
     * Handedness would need a classifier this pipeline does not have, and
     * guessing it from which wrist moves faster is exactly the sort of
     * plausible inference that is wrong for a left-hander once a match.
     */
    val ARM_EDGES: Set<Pair<Int, Int>> = setOf(5 to 7, 7 to 9, 6 to 8, 8 to 10)

    /**
     * Whether an edge should be drawn at all.
     *
     * Both ends must be confident. Drawing a limb to a keypoint the model is
     * unsure of produces a figure with an arm through its own chest, which reads
     * as a tracking failure rather than as low confidence.
     */
    fun edgeVisible(
        confidence: List<Float>,
        edge: Pair<Int, Int>,
        minConfidence: Float = NearPlayerSelector.MIN_KEYPOINT_CONFIDENCE,
    ): Boolean =
        edge.first < confidence.size && edge.second < confidence.size &&
            confidence[edge.first] >= minConfidence && confidence[edge.second] >= minConfidence
}

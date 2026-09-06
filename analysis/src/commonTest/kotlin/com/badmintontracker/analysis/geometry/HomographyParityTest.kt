package com.badmintontracker.analysis.geometry

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.fail

/**
 * Golden values captured by running badminton-tracker's own
 * src/utils/homography.ts under Node on these exact inputs.
 *
 * The point of this port is that on-device metres equal cloud metres, and
 * nothing else in the suite would notice a divergence that stayed
 * self-consistent: a transposed multiply or a dropped normalization step
 * still passes "corners map to corners". These numbers are the only check
 * anchoring the Kotlin to the implementation it was ported from.
 */
class HomographyParityTest {

    // A plausible 12-keypoint pixel layout, made by projecting the 12 court
    // positions through a synthetic camera matrix and feeding them back in.
    private val syntheticPixels = listOf(
        Point(640.0, 180.0),
        Point(645.3325388605181, 180.26101631201988),
        Point(646.7083272780119, 190.08158536388444),
        Point(641.3256344511517, 189.820516444247),
        Point(640.6596939646944, 184.88712059559333),
        Point(646.0171924375774, 185.1481687582994),
        Point(640.4640933644762, 183.43807941438513),
        Point(645.8141914257627, 183.6991193050735),
        Point(640.8558400843525, 186.34020307387686),
        Point(646.2207594692245, 186.60125853980412),
        Point(643.139469792881, 183.56861533399277),
        Point(643.5386289970485, 186.47074682660562),
    )

    private val expectedFourPoint = listOf(
        listOf(0.005545454545454545, 0.0028096969696969685, -3.8892121212121205),
        listOf(-1.1931451881553973e-18, 0.024038787878787878, -4.807757575757574),
        listOf(-1.1980019528290283e-19, 0.0009212121212121211, 0.47030303030303044),
    )

    private val expectedTwelvePoint = listOf(
        listOf(1.1459794723906611, -0.1546914439315154, -705.5824024223505),
        listOf(-0.06755912041091351, 1.3802265160584009, -205.20293582752745),
        listOf(-9.313056318373834e-05, 0.0009723462189984752, 0.8800048978759927),
    )

    @Test
    fun the_four_point_matrix_matches_the_typescript() {
        // Four correspondences make the system exactly determined, so this
        // covers the direct-solve branch rather than the normal equations.
        val h = calculateHomography(
            listOf(Point(600.0, 200.0), Point(1320.0, 200.0), Point(1700.0, 950.0), Point(220.0, 950.0)),
            COURT_KEYPOINT_POSITIONS.take(4),
        )!!
        h.shouldMatch(expectedFourPoint)
    }

    @Test
    fun the_twelve_point_matrix_matches_the_typescript() {
        // Twelve correspondences overdetermine it, exercising least squares.
        val h = calculateHomography(syntheticPixels, COURT_KEYPOINT_POSITIONS)!!
        h.shouldMatch(expectedTwelvePoint)
    }

    @Test
    fun applied_court_positions_match_the_typescript() {
        val h = calculateHomography(syntheticPixels, COURT_KEYPOINT_POSITIONS)!!
        // What the pipeline consumes is metres, not the matrix itself.
        syntheticPixels.zip(COURT_KEYPOINT_POSITIONS).forEach { (pixel, expected) ->
            val got = h.apply(pixel.x, pixel.y)!!
            if (abs(got.x - expected.x) > 1e-9 || abs(got.y - expected.y) > 1e-9) {
                fail("pixel (${pixel.x}, ${pixel.y}) gave (${got.x}, ${got.y}), wanted (${expected.x}, ${expected.y})")
            }
        }
    }

    private fun Matrix3x3.shouldMatch(expected: Matrix3x3) {
        for (r in 0..2) {
            for (c in 0..2) {
                val scale = maxOf(abs(expected[r][c]), 1e-12)
                if (abs(this[r][c] - expected[r][c]) / scale > 1e-9) {
                    fail("H[$r][$c] was ${this[r][c]}, wanted ${expected[r][c]}")
                }
            }
        }
    }
}

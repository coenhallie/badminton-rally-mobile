package com.badmintontracker.shared.local

import com.badmintontracker.analysis.geometry.Court
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [placeRallyLabels] on the Base panel's own geometry: the 300pt court card
 * both platforms draw, its two-metre margin, a 5pt dot, a 10pt ring with a
 * 2.5pt stroke, and a number the size an 11pt label measures at.
 */
class RallyLabelPlacementTest {

    /**
     * IMG_5892 as the Android Base screenshot lists it (task 15a): the whole
     * match 1.00 m behind the service line and 0.08 m left of centre, and five
     * rallies. Rally 4 sits level with the ring on its left, which is the row
     * that threw its "4" to the ring's far side; rallies 2 and 5 sit 8pt apart
     * beside the ring, which is where their numbers ran into each other.
     */
    @Test
    fun the_handheld_recording_numbers_every_dot_and_collides_nowhere() {
        val rallies = listOf(
            coach(behind = 2.03, across = 1.99),
            coach(behind = 0.38, across = -0.23),
            coach(behind = 1.40, across = 0.93),
            coach(behind = 0.89, across = -1.33),
            coach(behind = 0.65, across = -0.28),
        )
        val ring = coach(behind = 1.00, across = -0.08)
        val placed = place(rallies, ring)

        assertInvariants(rallies, ring, placed)
        // The "4" beside its own dot, on the side the ring is not, where it
        // used to be thrown past the ring.
        placed.single { it.mark == 3 }.left shouldBe rallies[3].x + DOT + GAP
        // The dots clear of the ring's row keep the default, on the right.
        placed.single { it.mark == 0 }.left shouldBe rallies[0].x + DOT + GAP
        placed.single { it.mark == 2 }.left shouldBe rallies[2].x + DOT + GAP
        // "2" and "5" no longer share any of the card: the "2" goes above its
        // dot, since its right is the ring and its left is dot 5, and the "5"
        // takes its own left, clear of the "4".
        placed.single { it.mark == 1 }.top shouldBe rallies[1].y - DOT - GAP - HEIGHT
        placed.single { it.mark == 4 }.left shouldBe rallies[4].x - DOT - GAP - WIDTH
        placed.map { it.mark } shouldBe listOf(0, 1, 2, 3, 4)
    }

    /**
     * IMG_5892 as the iOS Base screenshot lists it (a separate run, with its
     * own rally windows): rallies 2 and 5 are 8pt apart just above the ring,
     * which is where their numbers were drawn over each other.
     */
    @Test
    fun the_handheld_recording_on_ios_numbers_every_dot_and_keeps_2_and_5_apart() {
        val rallies = listOf(
            coach(behind = 2.00, across = 2.05),
            coach(behind = 0.39, across = -0.23),
            coach(behind = 0.45, across = -1.28),
            coach(behind = 2.17, across = 1.09),
            coach(behind = 0.67, across = -0.30),
        )
        val ring = coach(behind = 1.22, across = 0.13)
        val placed = place(rallies, ring)

        assertInvariants(rallies, ring, placed)
        // Every dot carries its number. 1 to 4 keep the default; the "5" has
        // the "2" on its right, the "3" on its left, dot 2 above and the ring
        // below, so it moves diagonally, below and to the left of its dot,
        // where it used to be dropped.
        placed.map { it.mark } shouldBe listOf(0, 1, 2, 3, 4)
        placed.filter { it.mark < 4 }.forEach { it.left shouldBe rallies[it.mark].x + DOT + GAP }
        val five = placed.single { it.mark == 4 }
        five.left shouldBe (rallies[4].x - DIAGONAL - WIDTH plusOrMinus TOLERANCE)
        five.top shouldBe (rallies[4].y + DIAGONAL plusOrMinus TOLERANCE)
    }

    @Test
    fun two_dots_a_tenth_of_a_metre_apart_across_get_two_clear_labels() {
        val rallies = listOf(coach(behind = 1.5, across = 0.0), coach(behind = 1.5, across = 0.1))
        val placed = place(rallies, ring = null)

        assertInvariants(rallies, null, placed)
        placed shouldHaveSize 2
        // The first number's right is the second dot, so it goes left; the
        // second keeps the default.
        placed[0].left shouldBe rallies[0].x - DOT - GAP - WIDTH
        placed[1].left shouldBe rallies[1].x + DOT + GAP
    }

    @Test
    fun two_dots_a_tenth_of_a_metre_apart_down_the_court_get_two_clear_labels() {
        val rallies = listOf(coach(behind = 1.5, across = 0.0), coach(behind = 1.6, across = 0.0))
        val placed = place(rallies, ring = null)

        assertInvariants(rallies, null, placed)
        placed shouldHaveSize 2
        // 3pt apart vertically: the second's right would sit on the first's
        // number, so it takes the left.
        placed[0].left shouldBe rallies[0].x + DOT + GAP
        placed[1].left shouldBe rallies[1].x - DOT - GAP - WIDTH
    }

    @Test
    fun a_dot_beside_the_ring_keeps_its_number_on_its_own_side() {
        // Half a metre left of the whole-match marker on its row: the right of
        // the dot is inside the ring, so the number goes to the dot's left
        // rather than past the ring, where it would read as the ring's.
        val rallies = listOf(coach(behind = 1.0, across = -0.5))
        val ring = coach(behind = 1.0, across = 0.0)
        val placed = place(rallies, ring)

        assertInvariants(rallies, ring, placed)
        placed.single().left shouldBe rallies[0].x - DOT - GAP - WIDTH
    }

    @Test
    fun a_dot_at_the_cards_right_edge_labels_to_its_left() {
        // 1.6 m out past the right sideline, inside the 2 m margin the card
        // draws: a number on the right would leave the card.
        val rallies = listOf(coach(behind = 1.0, across = Court.WIDTH_DOUBLES / 2 + 1.6))
        val placed = place(rallies, ring = null)

        assertInvariants(rallies, null, placed)
        placed.single().left shouldBe rallies[0].x - DOT - GAP - WIDTH
    }

    @Test
    fun five_rallies_on_one_spot_fill_right_left_above_below_and_only_then_drop_one() {
        // The fifth number has all eight slots tried: each diagonal corner
        // runs into the number beside or above or below it, so with every
        // slot colliding it, and only it, is left to the list.
        val rallies = List(5) { coach(behind = 1.0, across = 1.0) }
        val placed = place(rallies, ring = null)

        assertInvariants(rallies, null, placed)
        placed.map { it.mark } shouldBe listOf(0, 1, 2, 3)
        val d = rallies[0]
        placed[0].left shouldBe d.x + DOT + GAP
        placed[1].left shouldBe d.x - DOT - GAP - WIDTH
        placed[2].top shouldBe d.y - DOT - GAP - HEIGHT
        placed[3].top shouldBe d.y + DOT + GAP
    }

    @Test
    fun with_no_whole_match_marker_a_lone_dot_is_labelled_on_its_right() {
        val rallies = listOf(coach(behind = 1.0, across = 0.0))
        val placed = place(rallies, ring = null)
        placed.single().left shouldBe rallies[0].x + DOT + GAP
        placed.single().top shouldBe rallies[0].y - HEIGHT / 2
    }

    // ---- geometry and checks ----

    private data class At(val x: Float, val y: Float)

    /** A position said the way the panel's list says it, on the card. */
    private fun coach(behind: Double, across: Double): At {
        val x = Court.WIDTH_DOUBLES / 2 + across
        val y = Court.LENGTH / 2 + Court.SERVICE_LINE + behind
        return At(
            ((x + MARGIN_M) / TOTAL_W * CARD_W).toFloat(),
            ((y + MARGIN_M) / TOTAL_H * CARD_H).toFloat(),
        )
    }

    private fun place(rallies: List<At>, ring: At?): List<PlacedLabel> = placeRallyLabels(
        marks = rallies.map { RallyMark(it.x, it.y, WIDTH, HEIGHT) },
        dotRadius = DOT,
        gap = GAP,
        ringX = ring?.x ?: 0f,
        ringY = ring?.y ?: 0f,
        ringReach = if (ring == null) 0f else REACH,
        canvasWidth = CARD_W,
        canvasHeight = CARD_H,
    )

    /** How far the label's box is from a point; 0 when the point is inside it. */
    private fun distance(p: PlacedLabel, x: Float, y: Float): Float {
        val nx = x.coerceIn(p.left, p.left + WIDTH)
        val ny = y.coerceIn(p.top, p.top + HEIGHT)
        return hypot(nx - x, ny - y)
    }

    /** The gap between two labels' boxes along the axis they are apart on. */
    private fun apart(a: PlacedLabel, b: PlacedLabel): Float = maxOf(
        maxOf(b.left - (a.left + WIDTH), a.left - (b.left + WIDTH)),
        maxOf(b.top - (a.top + HEIGHT), a.top - (b.top + HEIGHT)),
    )

    /**
     * What every placement must hold whatever the medians round to: inside
     * the card, in one of its own dot's eight slots, and at least the gap it
     * keeps from its own dot away from every other number, the ring and every
     * other dot; numbers come out in rally order.
     */
    private fun assertInvariants(rallies: List<At>, ring: At?, placed: List<PlacedLabel>) {
        placed.map { it.mark } shouldBe placed.map { it.mark }.sorted()
        placed.forEach { p ->
            withClue("label ${p.mark + 1}") {
                assertTrue(p.left >= 0f && p.top >= 0f, "inside the card")
                assertTrue(p.left + WIDTH <= CARD_W && p.top + HEIGHT <= CARD_H, "inside the card")
                val own = rallies[p.mark]
                val slots = listOf(
                    own.x + DOT + GAP to own.y - HEIGHT / 2,
                    own.x - DOT - GAP - WIDTH to own.y - HEIGHT / 2,
                    own.x - WIDTH / 2 to own.y - DOT - GAP - HEIGHT,
                    own.x - WIDTH / 2 to own.y + DOT + GAP,
                    own.x - DIAGONAL - WIDTH to own.y + DIAGONAL,
                    own.x + DIAGONAL to own.y + DIAGONAL,
                    own.x - DIAGONAL - WIDTH to own.y - DIAGONAL - HEIGHT,
                    own.x + DIAGONAL to own.y - DIAGONAL - HEIGHT,
                )
                assertTrue(slots.any { (l, t) -> abs(l - p.left) < TOLERANCE && abs(t - p.top) < TOLERANCE }, "in a slot of its own dot")
                placed.filter { it !== p }.forEach { other ->
                    assertTrue(apart(p, other) >= GAP - TOLERANCE, "clear of label ${other.mark + 1}")
                }
                if (ring != null) assertTrue(distance(p, ring.x, ring.y) >= REACH + GAP - TOLERANCE, "clear of the ring")
                rallies.forEachIndexed { i, d ->
                    if (i != p.mark) assertTrue(distance(p, d.x, d.y) >= DOT + GAP - TOLERANCE, "clear of dot ${i + 1}")
                }
            }
        }
    }

    private companion object {
        const val MARGIN_M = 2.0
        const val TOTAL_W = Court.WIDTH_DOUBLES + 2 * MARGIN_M
        const val TOTAL_H = Court.LENGTH + 2 * MARGIN_M
        const val CARD_W = 300f
        val CARD_H = (CARD_W * TOTAL_H / TOTAL_W).toFloat()
        const val DOT = 5f
        const val GAP = 2f
        const val REACH = 10f + 2.5f / 2f

        /**
         * One digit of the 11pt/11sp label both platforms draw. Archivo
         * Medium's widest digit advances 6.31pt at 11pt, 6.87 with the role's
         * 0.05em tracking, and Android rounds a measured width up to a whole
         * pixel; its line box is 11 x 1.3 = 14.3 (iOS's natural line, 12.0,
         * is shorter). Rounded up to this.
         */
        const val WIDTH = 7.5f
        const val HEIGHT = 14.5f

        /** A diagonal slot's near corner is DOT + GAP from the dot's centre, at 45 degrees. */
        val DIAGONAL = ((DOT + GAP) / kotlin.math.sqrt(2.0)).toFloat()
        const val TOLERANCE = 1e-3f
    }
}

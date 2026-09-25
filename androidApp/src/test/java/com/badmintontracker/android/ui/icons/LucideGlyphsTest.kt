package com.badmintontracker.android.ui.icons

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.addPathNodes
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The match-list glyphs are Lucide's own path strings handed to Compose's SVG
 * parser, and three of them lean on SVG's implicit command rule: coordinates
 * after a moveto are linetos. A parser that read them as further movetos would
 * draw nothing for those strokes, and nothing on a JVM test would notice.
 */
class LucideGlyphsTest {

    @Test
    fun coordinates_after_an_absolute_moveto_are_linetos() {
        // hash's second slash.
        addPathNodes("M10 3 8 21") shouldBe listOf(PathNode.MoveTo(10f, 3f), PathNode.LineTo(8f, 21f))
    }

    @Test
    fun coordinates_after_a_relative_moveto_are_relative_linetos() {
        // person-standing's legs.
        addPathNodes("m9 20 3-6 3 6") shouldBe listOf(
            PathNode.RelativeMoveTo(9f, 20f),
            PathNode.RelativeLineTo(3f, -6f),
            PathNode.RelativeLineTo(3f, 6f),
        )
    }

    @Test
    fun every_match_list_glyph_builds_on_a_24_unit_viewport() {
        listOf(
            ShuttlIcons.Smartphone, ShuttlIcons.Cloud, ShuttlIcons.Film,
            ShuttlIcons.Flame, ShuttlIcons.PersonStanding, ShuttlIcons.Hash, ShuttlIcons.InfoCircle,
        ).forEach { icon ->
            icon.viewportWidth shouldBe 24f
            icon.viewportHeight shouldBe 24f
        }
    }
}

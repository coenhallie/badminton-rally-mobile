package com.badmintontracker.android.cliplist

import com.badmintontracker.shared.matchlist.MatchAnalysis
import com.badmintontracker.shared.matchlist.MatchListCopy
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * [legendGlyphKind]: every row of the shared [MatchListCopy.LEGEND] maps to the
 * glyph its own words describe, keyed on the label rather than on the row's
 * position. A positional pairing would still compile and still run after the
 * shared list changed shape. Mirrors iOS's MatchLegendTests.
 */
class MatchLegendTest {
    private val expected = mapOf(
        MatchListCopy.WHERE_PHONE to LegendGlyphKind.WherePhone,
        MatchListCopy.WHERE_CLOUD to LegendGlyphKind.WhereCloud,
        MatchListCopy.ANALYSIS_RALLIES to LegendGlyphKind.Analysis(MatchAnalysis.RALLIES),
        MatchListCopy.ANALYSIS_HEATMAP to LegendGlyphKind.Analysis(MatchAnalysis.HEATMAP),
        MatchListCopy.ANALYSIS_SKELETON to LegendGlyphKind.Analysis(MatchAnalysis.SKELETON),
        MatchListCopy.ANALYSIS_SCORE to LegendGlyphKind.Analysis(MatchAnalysis.SCORE),
    )

    @Test
    fun every_legend_row_maps_to_its_intended_glyph() {
        MatchListCopy.LEGEND.size shouldBe expected.size
        MatchListCopy.LEGEND.forEach { (label, _) -> legendGlyphKind(label) shouldBe expected[label] }
    }

    @Test
    fun an_unknown_label_maps_to_no_glyph_rather_than_guessing() {
        legendGlyphKind("not a real legend row") shouldBe null
    }

    @Test
    fun only_the_where_glyphs_are_bare_the_analyses_are_chipped() {
        LegendGlyphKind.WherePhone.isWhere shouldBe true
        LegendGlyphKind.WhereCloud.isWhere shouldBe true
        LegendGlyphKind.Analysis(MatchAnalysis.RALLIES).isWhere shouldBe false
    }
}

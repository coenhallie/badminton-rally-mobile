package com.badmintontracker.android.cliplist

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ThumbnailFallbackTest {

    @Test
    fun a_video_whose_picture_failed_shows_the_video_glyph_not_the_scores() {
        thumbnailFallback(hasVideo = true, hasScore = false) shouldBe ThumbnailFallback.VIDEO_GLYPH
    }

    @Test
    fun a_score_wins_over_either_glyph() {
        thumbnailFallback(hasVideo = true, hasScore = true) shouldBe ThumbnailFallback.SCORE
        thumbnailFallback(hasVideo = false, hasScore = true) shouldBe ThumbnailFallback.SCORE
    }

    @Test
    fun only_an_unstarted_scored_match_with_no_video_shows_the_score_glyph() {
        thumbnailFallback(hasVideo = false, hasScore = false) shouldBe ThumbnailFallback.SCORE_GLYPH
    }
}

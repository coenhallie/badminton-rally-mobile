package com.badmintontracker.analysis.court

import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class RidgesAndLinesTest {
    private val cam = SyntheticCourt.camera(w = 1280, h = 720, f = 1000.0)
    private val img = SyntheticCourt.render(cam, 1280, 720, floor = 0xFF3C8C46.toInt(), line = 0xFFEBEBEB.toInt())

    private fun work(): com.badmintontracker.analysis.camera.GreyImage {
        val (w, h) = longSideSize(img.width, img.height, 960)
        return areaDownsample(img.luma(), w, h)
    }

    @Test fun ridges_mark_the_painted_lines_and_little_else() {
        val r = ridges(work())
        val on = r.mask.count { it }
        on shouldBeGreaterThanOrEqual 2000
        (on < r.width * r.height / 20) shouldBe true
    }

    @Test fun candidate_lines_include_both_baselines_and_both_sidelines() {
        val lines = candidateLines(ridges(work()))
        (lines.size >= 10) shouldBe true
    }

    @Test fun two_families_are_found_and_hold_the_court_lines() {
        val fam = lineFamilies(candidateLines(ridges(work())), 960, 540).shouldNotBeNull()
        (fam.first.size >= 4 && fam.second.size >= 4) shouldBe true
    }

    @Test fun a_blank_frame_has_no_families() {
        val blank = com.badmintontracker.analysis.camera.GreyImage(960, 540, FloatArray(960 * 540) { 90f })
        lineFamilies(candidateLines(ridges(blank)), 960, 540) shouldBe null
    }
}

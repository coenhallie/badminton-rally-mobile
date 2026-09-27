# Court Auto-Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The marking screen finds the court by itself (twelve points placed to a fraction of a pixel), falls back to "tap four corners and the rest is placed", and lets the coach move any point.

**Architecture:** A pure-Kotlin detector in `:analysis` (`com.badmintontracker.analysis.court`), ported stage by stage from the frozen Python reference `tools/court/court_probe.py`. `:shared` gains the marking-state operations (history, select/move, apply a detection, overlay lines) and the copy. Android and iOS each hand the on-screen frame's pixels to the detector off the main thread and draw the result.

**Tech Stack:** Kotlin Multiplatform (`:analysis` jvm/android/ios, `:shared`), Jetpack Compose, SwiftUI + SKIE, kotest assertions, OpenCV/NumPy only in the Python tools.

**Spec:** `docs/plans/2026-09-27-court-auto-detect-design.md` (read it first; section numbers below refer to it). Reference implementation: `tools/court/court_probe.py`. Corpus: `~/shuttl-corpus/court-detect/manifest.json` + `frames/*.png`.

## Global Constraints

- Never write the em dash character in code, comments, docs, commit messages or UI copy. Use a plain "-".
- No `Co-Authored-By` trailer and no "Generated with" footer on commits.
- `:analysis` stays pure computation: no new dependencies, no OpenCV, no I/O in `commonMain`.
- The wire format of the marks does not change: `shared/.../model/CourtKeypoints.kt` keeps exactly its twelve keys plus `marked_at_s`.
- Output labels follow the keypoint table exactly (`COURT_KEYPOINT_POSITIONS`, `analysis/.../geometry/CourtGeometry.kt:78-91`): model `(0,0)` is the top-left of the image; SNL/SNR/CTN are on the top-of-frame half. The cloud reads them literally.
- Frozen parameters (design §4): work long side 960; fit long side cap 1920; ridge scales 1.0, 1.8, 3.0; contrast box 41x41, `best/(sd+6) > 1.2`, `best > 4`; component diagonal >= 3% of long side; Hough 720 angle bins, ±2.5° voting (11 votes), suppression ±8 bins, stop at 40 lines or votes < 0.025 x long side; support band 1.5 px; vanishing-point inlier angle 1.5°; horizon margin 0.01 x height; 9 lines per family (14 and 60 lines for corners); quad corners within 2 image sizes of centre; gates: court area in (0.02, 60) x image area, net length > 0.08 x width; score samples 0.5 m then top 60 rescored at 0.1 m, weight capped 40 px, on = distance <= 1.5 px, off = -0.5; refinement samples every 0.05 m (2% trimmed), window `1.5 x width + tol + 3`, profile step 0.5 px, baseline outer 20%, peak >= 8, width in [0.3w, 2.5w+2], Huber scale `1.4826 x median|r| + 0.1` knee 1.5, 10 Gauss-Newton iterations per round, tol 12 halving to 2.5, stop when step < 0.02 px at the floor, max 8 rounds, >= 30 observations; accept: step <= 0.1 px, residual x (1920/long side) <= 1.5 px, all 12 keypoints in frame, >= 2 across and >= 2 along lines with >= 40% support over >= 5% of the long side visible, `maxResidualM` <= 0.05 m; corners: 5% of work long side, fallback tolerance 30 px.
- Pixel-centre convention between the work image and source pixels: `x_src = (x_work + 0.5) * s - 0.5` (the same as `Luma.trackerToSource`).
- UI copy is exactly the strings in Task 6, shared by both platforms.
- Build env: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`; iOS: `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`, `xattr -cr iosApp/Sources iosApp/Tests iosApp/UITests iosApp/Resources iosApp/iosApp.xcodeproj 2>/dev/null`, `xcodegen generate` after adding Swift files, then `xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp -destination "platform=iOS Simulator,name=iPhone 17 Pro" -derivedDataPath iosApp/build/DerivedData CODE_SIGNING_ALLOWED=NO`.
- Android: `ANDROID_SERIAL=emulator-5554`, `ADB_MDNS=0 ADB_MDNS_OPENSCREEN=0` for adb. Never sign the emulator or simulator out.

## Review Focus

1. A portrait frame (height > width): scaling uses the long side everywhere; a synthetic portrait court is found or refused, never throws (Task 4 test `portrait_frame_is_handled`).
2. A tiny or degenerate frame (16x16, one colour, 1x1): `find` returns not found with a reason, no exception (Task 4 test `degenerate_frames_are_not_found`).
3. A detection that arrives after the coach already tapped: discarded, the taps stay (Task 7 test `detection_after_a_tap_is_discarded`, Task 8 same rule through the shared function).
4. The detector throwing (out of memory, a bug): treated as not found, the screen stays usable (Task 7 test `a_failing_finder_falls_back_to_manual`).
5. A 4K frame: the fit image is capped at 1920 and keypoints come back in source pixels (Task 5 test `four_k_frame_keypoints_are_in_source_pixels`).

---

## File structure

`analysis/src/commonMain/kotlin/com/badmintontracker/analysis/court/`
- `RgbImage.kt` - the input type and luma conversion.
- `ImageOps.kt` - area downsample, Gaussian blur, 3x3 Sobel second derivatives, box mean/std, exact Euclidean distance transform, connected components. `internal`.
- `CourtModel.kt` - model lines (search and line-centre), sample points, rectangles, keypoint positions. `internal`.
- `Ridges.kt` - §4.3. `internal`.
- `Lines.kt` - oriented Hough, support extents, vanishing-point families, horizon. `internal`.
- `Search.kt` - §4.5 and the corner-guided variant of §4.8. `internal`.
- `Refine.kt` - §4.6. `internal`.
- `CourtFinder.kt` - public entry points, acceptance (§4.7), `CourtFinding`.

Tests: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/court/` (`SyntheticCourt.kt` renderer, `ImageOpsTest.kt`, `RidgesAndLinesTest.kt`, `SearchTest.kt`, `CourtFinderTest.kt`), `analysis/src/jvmTest/kotlin/com/badmintontracker/analysis/court/CourtCorpusTest.kt`.

`shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/court/`
- `CourtMarkingModel.kt` - modified: history, selection, tap, detection, overlay lines, copy.
- `CourtAutoMarking.kt` - `CourtFinding` to marking points; the finder status enum and its copy.
- `shared/src/iosMain/.../local/BulkInterop.kt` - `rgbImage(...)`.

Android: `CourtMarkingViewModel.kt`, `CourtMarkingScreen.kt`, `AuthGate.kt:590`, new `CourtPixels.kt`.
iOS: `CourtMarkingView.swift`, new `CourtFinderBridge.swift`, tests.

---

### Task 1: Image operations and the court model

**Files:**
- Create: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/court/RgbImage.kt`, `ImageOps.kt`, `CourtModel.kt`
- Test: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/court/ImageOpsTest.kt`

**Interfaces:**
- Consumes: `com.badmintontracker.analysis.camera.GreyImage(width, height, pixels: FloatArray)`, `com.badmintontracker.analysis.geometry.COURT_KEYPOINT_POSITIONS`.
- Produces:
  - `class RgbImage(val width: Int, val height: Int, val argb: IntArray)` (public; `require(argb.size == width * height)`), `internal fun RgbImage.luma(): GreyImage` (`0.299 R + 0.587 G + 0.114 B`).
  - `internal fun areaDownsample(src: GreyImage, outWidth: Int, outHeight: Int): GreyImage` (same box rule as `Luma.downsampleWith`, on floats).
  - `internal fun longSideSize(width: Int, height: Int, longSide: Int): Pair<Int, Int>` (no upscaling; `max(1, round(...))`).
  - `internal fun gaussianBlur(src: GreyImage, sigma: Double): GreyImage` (separable, radius `ceil(3 sigma)`, border reflect-101 like OpenCV).
  - `internal class SecondDerivatives(val dxx: FloatArray, val dyy: FloatArray, val dxy: FloatArray)`, `internal fun sobel2(src: GreyImage): SecondDerivatives` (OpenCV `Sobel` ksize 3: dxx = [1,-2,1] in x with [1,2,1] in y; dyy transposed; dxy = [-1,0,1] x [-1,0,1]; reflect-101 border).
  - `internal fun boxMeanStd(src: GreyImage, size: Int): Pair<FloatArray, FloatArray>` (box of `size x size`, reflect-101, std = `sqrt(max(E[x²]-E[x]², 0))`).
  - `internal fun distanceTransform(mask: BooleanArray, width: Int, height: Int): FloatArray` (exact Euclidean distance to the nearest `true`, Felzenszwalb-Huttenlocher; no `true` pixel gives `Float.MAX_VALUE`).
  - `internal fun keepLongComponents(mask: BooleanArray, width: Int, height: Int, minDiagonal: Double): BooleanArray` (8-connected; keep components whose bounding-box diagonal `hypot(bw, bh)` >= minDiagonal, where `bw = maxX - minX + 1`).
  - `internal object CourtModel` with `W = 6.1`, `L = 13.4`, `S = 1.98`, `LS = 0.76`, `O = (6.1 - 5.18) / 2`, `ACROSS = doubleArrayOf(0.0, LS, L/2-S, L/2+S, L-LS, L)`, `ALONG = doubleArrayOf(0.0, O, W-O, W)`, `SEGMENTS: List<Segment>` (6 across, 4 along, then centre `(W/2,0)-(W/2,L/2-S)` and `(W/2,L/2+S)-(W/2,L)`: 12 segments, indices 0-5 across, 6-11 along), `CENTRE_SEGMENTS: List<Segment>` (line centres: `h = 0.02`; across `h, LS+h, L/2-S-h, L/2+S+h, L-LS-h, L-h`; along `h, O+h, W-O-h, W-h`; centre halves as in SEGMENTS), `KEYPOINTS: List<Point>` = `COURT_KEYPOINT_POSITIONS`, `fun samples(step: Double): ModelSamples` (for each segment `n = max(2, floor(len/step)+1)` points evenly including both ends; `ModelSamples(x: DoubleArray, y: DoubleArray, segment: IntArray)`), and `RECTANGLES: List<DoubleArray>` (360 entries of 8 doubles: for `a1<a2` in 0..5, `b1<b2` in 0..3, `x1=ALONG[b1], x2=ALONG[b2], y1=ACROSS[a1], y2=ACROSS[a2]`, the four corner orders `[(x1,y1),(x2,y1),(x2,y2),(x1,y2)]`, `[(x1,y1),(x1,y2),(x2,y2),(x2,y1)]`, `[(x2,y1),(x1,y1),(x1,y2),(x2,y2)]`, `[(x1,y2),(x1,y1),(x2,y1),(x2,y2)]`, in that order).
  - `internal class Segment(val ax: Double, val ay: Double, val bx: Double, val by: Double)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.badmintontracker.analysis.court

import com.badmintontracker.analysis.camera.GreyImage
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.hypot
import kotlin.test.Test

class ImageOpsTest {
    @Test fun luma_uses_bt601_weights() {
        val img = RgbImage(2, 1, intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()))
        val y = img.luma()
        y.pixels[0].toDouble() shouldBe (0.299 * 255 plusOrMinus 1e-3)
        y.pixels[1].toDouble() shouldBe (0.587 * 255 plusOrMinus 1e-3)
    }

    @Test fun long_side_size_keeps_aspect_and_never_upscales() {
        longSideSize(1920, 1080, 960) shouldBe (960 to 540)
        longSideSize(1080, 1920, 960) shouldBe (540 to 960)
        longSideSize(640, 360, 960) shouldBe (640 to 360)
    }

    @Test fun distance_transform_is_exact_euclidean() {
        val w = 9; val h = 7
        val mask = BooleanArray(w * h); mask[3 * w + 4] = true
        val d = distanceTransform(mask, w, h)
        for (y in 0 until h) for (x in 0 until w)
            d[y * w + x].toDouble() shouldBe (hypot((x - 4).toDouble(), (y - 3).toDouble()) plusOrMinus 1e-4)
    }

    @Test fun distance_transform_without_pixels_is_infinite() {
        distanceTransform(BooleanArray(4), 2, 2).all { it == Float.MAX_VALUE } shouldBe true
    }

    @Test fun sobel2_of_a_quadratic_is_its_curvature() {
        // f = x^2 has dxx = 2 (Sobel scales the [1,-2,1] by the [1,2,1] sum = 4) and dyy = dxy = 0 inside.
        val w = 12; val h = 10
        val img = GreyImage(w, h, FloatArray(w * h) { val x = it % w; (x * x).toFloat() })
        val d = sobel2(img)
        d.dxx[5 * w + 6].toDouble() shouldBe (8.0 plusOrMinus 1e-4)
        d.dyy[5 * w + 6].toDouble() shouldBe (0.0 plusOrMinus 1e-4)
        d.dxy[5 * w + 6].toDouble() shouldBe (0.0 plusOrMinus 1e-4)
    }

    @Test fun gaussian_blur_preserves_a_constant_and_the_mean() {
        val img = GreyImage(20, 20, FloatArray(400) { if (it == 210) 400f else 0f })
        val b = gaussianBlur(img, 1.8)
        b.pixels.sum().toDouble() shouldBe (400.0 plusOrMinus 0.5)
        gaussianBlur(GreyImage(5, 5, FloatArray(25) { 7f }), 3.0).pixels.all { kotlin.math.abs(it - 7f) < 1e-4 } shouldBe true
    }

    @Test fun box_std_of_a_checkerboard_is_half_the_step() {
        val w = 50; val h = 50
        val img = GreyImage(w, h, FloatArray(w * h) { if ((it % w + it / w) % 2 == 0) 100f else 0f })
        val (mean, sd) = boxMeanStd(img, 41)
        mean[25 * w + 25].toDouble() shouldBe (50.0 plusOrMinus 0.2)
        sd[25 * w + 25].toDouble() shouldBe (50.0 plusOrMinus 0.2)
    }

    @Test fun short_components_are_dropped() {
        val w = 40; val h = 10
        val m = BooleanArray(w * h)
        for (x in 2..35) m[5 * w + x] = true   // long: diagonal 34
        m[1 * w + 1] = true; m[1 * w + 2] = true // short: diagonal ~2.2
        val kept = keepLongComponents(m, w, h, 10.0)
        kept[5 * w + 20] shouldBe true
        kept[1 * w + 1] shouldBe false
    }

    @Test fun court_model_has_twelve_segments_and_360_rectangles() {
        CourtModel.SEGMENTS.size shouldBe 12
        CourtModel.CENTRE_SEGMENTS.size shouldBe 12
        CourtModel.RECTANGLES.size shouldBe 360
        CourtModel.KEYPOINTS.size shouldBe 12
        CourtModel.CENTRE_SEGMENTS[0].ay shouldBe (0.02 plusOrMinus 1e-12)
        CourtModel.CENTRE_SEGMENTS[2].ay shouldBe (13.4 / 2 - 1.98 - 0.02 plusOrMinus 1e-12)
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :analysis:jvmTest --tests '*court.ImageOpsTest*'`
Expected: FAIL (unresolved references).

- [ ] **Step 3: Implement** `RgbImage.kt`, `ImageOps.kt`, `CourtModel.kt` per the Interfaces block. Match OpenCV's reflect-101 borders (`-1 -> 1`, `n -> n-2`). The distance transform: two passes of the 1-D lower-envelope algorithm over squared distances (columns then rows), `sqrt` at the end; use `1e20` for "no pixel" inside and map results `>= 1e19` to `Float.MAX_VALUE`. Connected components: iterative flood fill with an `IntArray` stack (no recursion).

- [ ] **Step 4: Run the tests** (`./gradlew :analysis:jvmTest --tests '*court.ImageOpsTest*'`) and `./gradlew :analysis:iosSimulatorArm64Test --tests '*court.ImageOpsTest*'`. Expected: PASS on both.

- [ ] **Step 5: Commit** `git add analysis/src && git commit -m "feat(analysis): image operations and the court model for court detection"`

---

### Task 2: Line pixels, candidate lines, vanishing-point families

**Files:**
- Create: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/court/Ridges.kt`, `Lines.kt`
- Create: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/court/SyntheticCourt.kt`
- Test: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/court/RidgesAndLinesTest.kt`

**Interfaces:**
- Consumes: Task 1.
- Produces:
  - `internal class RidgeMap(val width: Int, val height: Int, val mask: BooleanArray, val normal: FloatArray)`; `internal fun ridges(work: GreyImage): RidgeMap` (design §4.3; `probe: ridge()`).
  - `internal class CandidateLine(val rho: Double, val theta: Double, val votes: Double, val ax: Double, val ay: Double, val bx: Double, val by: Double)` (normal form `x cos(theta) + y sin(theta) = rho`; `a`, `b` the support extent); `internal fun candidateLines(r: RidgeMap, maxLines: Int = 40): List<CandidateLine>` (probe `hough()` + `support()`; lines with fewer than 5 support pixels are dropped).
  - `internal class LineFamilies(val first: List<CandidateLine>, val second: List<CandidateLine>)`; `internal fun lineFamilies(lines: List<CandidateLine>, width: Int, height: Int): LineFamilies?` (probe `vp_groups()` + the horizon filter in `search()`; null if fewer than two families).
  - Test helper (commonTest): `internal object SyntheticCourt { fun camera(side: Double = 0.0, height: Double = 4.5, back: Double = 6.0, f: Double = 1500.0, w: Int = 1920, h: Int = 1080): DoubleArray` (court->pixel homography, row-major, normalised `h[8] = 1`, exactly `tools/court/synth.py camera()`); `fun render(h: DoubleArray, w: Int, h: Int, floor: Int, line: Int, supersample: Int = 3, noise: Double = 4.0, blur: Double = 0.8, distractors: Boolean = true, occluders: Boolean = true, seed: Int = 7): RgbImage` (exactly `synth.py render()`, including the pixel-centre fix `X_big = ss*x + (ss-1)/2`); `fun truth(h: DoubleArray): List<Point>` (the 12 table keypoints projected through the canonically relabelled `h`: mirror x if `(0,0)` projects right of `(W,0)`, then mirror y if `(0,0)` projects below `(0,L)`) }`.

- [ ] **Step 1: Write the failing tests**

```kotlin
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
```

- [ ] **Step 2: Run to verify they fail** (`./gradlew :analysis:jvmTest --tests '*court.RidgesAndLinesTest*'`).

- [ ] **Step 3: Implement** `SyntheticCourt.kt` (test source), `Ridges.kt`, `Lines.kt`, porting `court_probe.py` `ridge`, `hough`, `support`, `vp_groups` and the horizon block of `search` line for line, with every constant from Global Constraints. Specifics: the ridge normal for a bright line is `theta + pi/2` and for a dark line `theta`, `theta = 0.5 atan2(2 dxy, dxx - dyy)`; the per-pixel scale kept is the one with the largest response; non-maximum suppression samples the response bilinearly at `(x ± cos n, y ± sin n)`; Hough angle bin `t` is `t * pi / 720`, vote `rho = round(x cos + y sin) + D` with `D = ceil(hypot(w, h)) + 1`; suppression wraps the angle index modulo 720. Vanishing point of lines i, j is `cross(l_i, l_j)` with `l = (cos, sin, -rho)`, normalised; a line is an inlier when the angle between its support direction and the direction from its support midpoint to the point (or the point's own direction when `|z| < 1e-9`) is under 1.5°; weight is the summed votes; ties keep the first pair found (i < j order). Horizon `cross(vp1, vp2)`; applied only if `|(hx, hy)| > 1e-9 * max(1, |hz|)`; floor side is the sign at `(w/2, h)`; keep a line when `floor * (horizon . midpoint) > 0.01 * h`.

- [ ] **Step 4: Run** the tests on jvm and iosSimulatorArm64. Expected: PASS.

- [ ] **Step 5: Commit** `git commit -m "feat(analysis): line pixels, candidate lines and their two directions"`

---

### Task 3: The search

**Files:**
- Create: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/court/Search.kt`
- Test: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/court/SearchTest.kt`

**Interfaces:**
- Consumes: Tasks 1-2.
- Produces:
  - `internal class SearchResult(val h: DoubleArray, val score: Double, val lines: Int, val quads: Int)` (`h` court metres -> work pixels, row-major, `h[8] = 1`, canonically labelled).
  - `internal fun searchCourt(work: GreyImage): SearchResult?` (§4.5, probe `search()` + `canon()`).
  - `internal fun searchCourtNear(work: GreyImage, cornersWork: List<Point>, radius: Double): SearchResult?` (§4.8, probe `search_near()`: 60 lines, 14 per family, only placements whose 4 corners are each within `radius` of the taps as a set over the 8 corner orders listed in the probe, best fine score).
  - `internal fun canonical(h: DoubleArray): DoubleArray` and `internal fun project(h: DoubleArray, x: Double, y: Double): Point?` (null when the third coordinate is `<= 1e-12`).
  - `internal fun gatesPass(h: DoubleArray, width: Int, height: Int): Boolean`.
- Implementation note (design §4.5): compute each quad's unit-square homography once (`squareToQuad`, the closed-form Heckbert solution), then for each rectangle `H = Hq * A_rect` where `A_rect` maps the rectangle's four corners (in the listed order) to `(0,0),(1,0),(1,1),(0,1)`. Because the corner orders are not all orientation-preserving, `A_rect` is the affine map fitted to the first three corners; check the fourth maps to `(0,1)` within 1e-9 (it does for all 360 by construction; assert it once in the model's init). The coarse score uses `CourtModel.samples(0.5)`, the rescoring `samples(0.1)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.badmintontracker.analysis.court

import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlin.math.hypot
import kotlin.test.Test

class SearchTest {
    @Test fun the_search_lands_on_the_court_to_a_few_work_pixels() {
        for ((i, cam) in listOf(
            SyntheticCourt.camera(w = 1280, h = 720, f = 1000.0),
            SyntheticCourt.camera(side = 1.5, height = 3.0, back = 4.0, w = 1280, h = 720, f = 1000.0),
        ).withIndex()) {
            val img = SyntheticCourt.render(cam, 1280, 720, floor = 0xFF3C8C46.toInt(), line = 0xFFEBEBEB.toInt())
            val (w, h) = longSideSize(1280, 720, 960)
            val s = searchCourt(areaDownsample(img.luma(), w, h)).shouldNotBeNull()
            val scale = 960.0 / 1280.0
            SyntheticCourt.truth(cam).forEachIndexed { k, t ->
                val p = project(s.h, CourtModel.KEYPOINTS[k].x, CourtModel.KEYPOINTS[k].y).shouldNotBeNull()
                if (t.x in 0.0..1279.0 && t.y in 0.0..719.0)
                    hypot(p.x - ((t.x + 0.5) * scale - 0.5), p.y - ((t.y + 0.5) * scale - 0.5)) shouldBeLessThan 4.0
            }
            println("case $i: lines ${s.lines} quads ${s.quads} score ${s.score}")
        }
    }

    @Test fun canonical_puts_the_origin_top_left() {
        val cam = SyntheticCourt.camera(w = 1280, h = 720, f = 1000.0)
        val c = canonical(cam)
        val o = project(c, 0.0, 0.0)!!; val x = project(c, 6.1, 0.0)!!; val y = project(c, 0.0, 13.4)!!
        (o.x < x.x && o.y < y.y) shouldBe true
    }
}
```

(add `import io.kotest.matchers.shouldBe`.)

- [ ] **Step 2: Run to verify they fail** (`./gradlew :analysis:jvmTest --tests '*court.SearchTest*'`).
- [ ] **Step 3: Implement** `Search.kt`. Keep allocation out of the inner loop: preallocate the sample arrays once per call; score with early exit when a sample is behind the camera. Keep the top 60 coarse scores in a fixed-size min-heap.
- [ ] **Step 4: Run** on jvm and iosSimulatorArm64. Expected: PASS. Note the printed timing of the second case.
- [ ] **Step 5: Commit** `git commit -m "feat(analysis): search for the court among line quadrilaterals"`

---

### Task 4: Refinement, acceptance and the public finder

**Files:**
- Create: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/court/Refine.kt`, `CourtFinder.kt`
- Test: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/court/CourtFinderTest.kt`

**Interfaces:**
- Consumes: Tasks 1-3; `com.badmintontracker.analysis.geometry.CourtKeypoints`, `.homography()`, `.maxResidualM()`, `Matrix3x3`.
- Produces:
  - `internal class Refinement(val h: DoubleArray, val rmsPx: Double, val observations: Int, val lastStepPx: Double, val rounds: Int)`; `internal fun refineCourt(fit: GreyImage, h0: DoubleArray, tolerance0: Double = 12.0): Refinement?` (§4.6, probe `refine3` with `_measure` and `_solve`; the Jacobian may be analytic: for residual `r = n . (q - H(a))` use central differences with step `1e-6 * max(|h_k|, 1e-3)` like the probe, or derive it; either must pass the synthetic test).
  - `data class CourtFinding(val keypoints: CourtKeypoints?, val homography: Matrix3x3?, val reason: String, val residualPx: Double?, val millis: Long) { val found: Boolean get() = keypoints != null }` (public; `homography` maps court metres to source pixels).
  - `object CourtFinder { fun find(image: RgbImage): CourtFinding; fun fromCorners(image: RgbImage, corners: List<Point>): CourtFinding }` (public; never throws: any exception becomes `CourtFinding(null, null, "error: <message>", null, ms)`). `corners` are TL, TR, BR, BL in source pixels as tapped.
  - Acceptance exactly §4.7, the line-support check computed on the work image's ridge mask and distance transform (probe `line_support`).
- Scaling: work image `longSideSize(w, h, 960)`, fit image `longSideSize(w, h, 1920)` (the source itself when smaller). Work -> fit and fit -> source use the pixel-centre rule of Global Constraints.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.badmintontracker.analysis.court

import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.hypot
import kotlin.test.Test

class CourtFinderTest {
    private val colours = listOf(
        0xFF3C8C46.toInt() to 0xFFEBEBEB.toInt(),
        0xFFD2AA78.toInt() to 0xFFF0F0F0.toInt(),
        0xFFC86E46.toInt() to 0xFFE6C828.toInt(),
        0xFF969696.toInt() to 0xFFFAFAFA.toInt(),
    )
    private val cams = listOf(
        SyntheticCourt.camera(w = 1280, h = 720, f = 1000.0),
        SyntheticCourt.camera(side = 1.5, height = 3.0, back = 4.0, w = 1280, h = 720, f = 1000.0),
        SyntheticCourt.camera(side = -2.0, height = 6.0, back = 8.0, w = 1280, h = 720, f = 870.0),
    )

    @Test fun synthetic_courts_are_found_to_a_fraction_of_a_pixel() {
        val errors = mutableListOf<Double>()
        for (cam in cams) for ((floor, line) in colours) {
            val img = SyntheticCourt.render(cam, 1280, 720, floor, line)
            val f = CourtFinder.find(img)
            val truth = SyntheticCourt.truth(cam)
            if (truth.all { it.x in 0.0..1279.0 && it.y in 0.0..719.0 }) {
                f.found shouldBe true
                val k = f.keypoints!!
                listOf(k.topLeft, k.topRight, k.bottomRight, k.bottomLeft, k.netLeft, k.netRight,
                    k.serviceLineNearLeft, k.serviceLineNearRight, k.serviceLineFarLeft,
                    k.serviceLineFarRight, k.centerNear, k.centerFar).forEachIndexed { i, p ->
                    errors += hypot(p.x - truth[i].x, p.y - truth[i].y)
                }
            }
        }
        errors.sorted()[errors.size / 2] shouldBeLessThan 0.1
        errors.max() shouldBeLessThan 0.5
    }

    @Test fun frames_without_a_court_are_not_found() {
        val grey = RgbImage(640, 360, IntArray(640 * 360) { 0xFF808080.toInt() })
        CourtFinder.find(grey).found shouldBe false
        val rnd = kotlin.random.Random(3)
        val noise = RgbImage(640, 360, IntArray(640 * 360) { 0xFF000000.toInt() or rnd.nextInt(0xFFFFFF) })
        CourtFinder.find(noise).found shouldBe false
    }

    @Test fun degenerate_frames_are_not_found() {
        CourtFinder.find(RgbImage(1, 1, intArrayOf(0xFFFFFFFF.toInt()))).found shouldBe false
        CourtFinder.find(RgbImage(16, 16, IntArray(256) { 0xFF102030.toInt() })).found shouldBe false
    }

    @Test fun portrait_frame_is_handled() {
        val cam = SyntheticCourt.camera(w = 720, h = 1280, f = 700.0, height = 6.0, back = 8.0)
        val img = SyntheticCourt.render(cam, 720, 1280, 0xFF3C8C46.toInt(), 0xFFEBEBEB.toInt())
        val f = CourtFinder.find(img)
        if (f.found) {
            val t = SyntheticCourt.truth(cam)[0]
            hypot(f.keypoints!!.topLeft.x - t.x, f.keypoints!!.topLeft.y - t.y) shouldBeLessThan 1.0
        } else println("portrait: not found (${f.reason})")
    }

    @Test fun four_corners_complete_the_court() {
        val cam = cams[1]
        val img = SyntheticCourt.render(cam, 1280, 720, colours[0].first, colours[0].second)
        val t = SyntheticCourt.truth(cam)
        val rnd = kotlin.random.Random(11)
        val taps = t.take(4).map { com.badmintontracker.analysis.geometry.Point(it.x + rnd.nextDouble(-10.0, 10.0), it.y + rnd.nextDouble(-10.0, 10.0)) }
        val f = CourtFinder.fromCorners(img, taps)
        f.found shouldBe true
        hypot(f.keypoints!!.centerFar.x - t[11].x, f.keypoints!!.centerFar.y - t[11].y) shouldBeLessThan 0.5
    }
}
```

- [ ] **Step 2: Run to verify they fail** (`./gradlew :analysis:jvmTest --tests '*court.CourtFinderTest*'`).
- [ ] **Step 3: Implement** `Refine.kt` (measure/solve rounds, Huber IRLS, fixed polarity after the first round) and `CourtFinder.kt` (orchestration, acceptance, `CourtKeypoints` built from the canonical homography's projections of `CourtModel.KEYPOINTS`, `homography` as `Matrix3x3`). Wrap `find` and `fromCorners` bodies in `try/catch (e: Throwable)`; return the reason. Time with `kotlin.time.TimeSource.Monotonic`.
- [ ] **Step 4: Run** on jvm and iosSimulatorArm64. Expected: PASS. If the median is above 0.1 px, compare `rmsPx` and the observation count with `python3 tools/court/synth.py` for the same scene before changing any constant.
- [ ] **Step 5: Commit** `git commit -m "feat(analysis): refine and accept a court fit; CourtFinder.find and fromCorners"`

---

### Task 5: Corpus test on the host

**Files:**
- Create: `analysis/src/jvmTest/kotlin/com/badmintontracker/analysis/court/CourtCorpusTest.kt`

**Interfaces:**
- Consumes: `CourtFinder`, `RgbImage`, `~/shuttl-corpus/court-detect/manifest.json` (keys: `frames[]` with `name, split, court, width, height, keypoints?` and `references.img_5892_marking_frame.keypoints`, `references.p743d_human.frames/keypoints`; frame PNGs in `frames/`), `~/shuttl-corpus/IMG_5892/H_ideal.json` (`{"H": 3x3}`, image px -> court metres).
- Produces: nothing for later tasks; a measured report printed to stdout.

- [ ] **Step 1: Write the test** (skips loudly without the corpus; `ANALYSIS_REQUIRE_CORPUS=1` makes the skip a failure, like `CameraMotionCorpusTest`). Load PNGs with `javax.imageio.ImageIO.read(file).getRGB(0, 0, w, h, null, 0, w)`. Four test methods:
  1. `every_label_is_reproduced`: for each of the 81 frames, `find(...).found == court`; on failure list every mismatch with its `reason`, then fail.
  2. `keypoints_match_the_reference_implementation`: for each accepted frame, per-keypoint distance to the manifest's `keypoints`; assert per-frame median <= 1.0 px and max <= 3.0 px; print the worst five frames.
  3. `accuracy_against_independent_references`: `frames/m5892_ref.png` vs `img_5892_marking_frame` keypoints: fit a homography from the found keypoints to `COURT_KEYPOINT_POSITIONS` (`calculateHomography`), map a 9x9 grid over `x in [-0.5, 6.6]`, `y in [6.7, 14.9]` through `inverse(H_ideal)` to pixels and back through the fit; assert median error <= 0.03 m. Paris frames listed in `p743d_human.frames` vs the human keypoints, resolving each near/far pair (6<->8, 7<->9, 10<->11) by the nearer of the two: median <= 6 px.
  4. `four_corner_completion_survives_tap_error`: frames `hall_240, m5892_2, hk1_15, cz_3, dark_80, par1_20, b2eab_8, hall_450`, 3 trials each with Gaussian tap noise σ = 15 px (seeded `Random(3)`), each within 2 px (max over keypoints) of `find`'s keypoints on the same frame.
  Also `four_k_frame_keypoints_are_in_source_pixels`: upscale `m5892_2` 2x (nearest) to 3840x2160, `find`, compare with the 1080p keypoints x 2 (+0.5 pixel-centre shift: `x4k = 2 x + 0.5`) within 1.5 px median. Print total and per-frame milliseconds (median, max).

- [ ] **Step 2: Run** `ANALYSIS_REQUIRE_CORPUS=1 ./gradlew :analysis:jvmTest --tests '*CourtCorpusTest*' -i`. Expected: PASS. A failure is a port defect until shown otherwise: rerun `python3 tools/court/court_probe.py ~/shuttl-corpus/court-detect/frames/<name>.png` and compare stage counts (`manifest.frames[].stages`: lines, groups, quads, rms, step) with the Kotlin values (log them from a debug hook in the test, not from production code).
- [ ] **Step 3: Commit** `git commit -m "test(analysis): court detection on the 81-frame corpus"`

---

### Task 6: The marking model

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/court/CourtMarkingModel.kt`
- Create: `shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/court/CourtAutoMarking.kt`
- Test: `shared/src/commonTest/kotlin/com/badmintontracker/shared/localvideo/court/CourtMarkingModelTest.kt` (extend), `CourtAutoMarkingTest.kt` (new)

**Interfaces:**
- Consumes: `com.badmintontracker.analysis.court.CourtFinding`, the shared `CourtKeypoints.toAnalysis()` conversion (find it with `grep -rn "fun .*toAnalysis" shared/src/commonMain`), analysis `homography()` and `invert3x3`, `Matrix3x3.apply`.
- Produces (all on `CourtMarkingState` unless stated):
  - new fields `val history: List<List<CourtPoint>> = emptyList()`, `val selected: Int? = null` (both last in the constructor; SKIE: Swift callers construct through `restored`, so no Swift call site changes).
  - `fun tap(displayX: Float, displayY: Float, displayWidth: Float, displayHeight: Float, hitRadiusDisplay: Float): CourtMarkingState` - incomplete: `place` (pushes history); complete and nothing selected: select the nearest point within the radius (converted to source pixels with the same scale as `place`), else unchanged; complete and one selected: a tap within the radius of the selected point deselects, anywhere else moves it there (pushes history, clears selection).
  - `place` pushes the current points onto `history`; `undo()` pops `history` when non-empty (clears selection), else drops the last point as today; `clear()` pushes history too.
  - `fun withDetected(points: List<CourtPoint>): CourtMarkingState` - requires 12 points; pushes history; sets `markedAtSeconds = frameSeconds` when the marking was empty.
  - `fun justReachedFourCorners(previous: CourtMarkingState): Boolean` = `previous.points.size == 3 && points.size == 4`.
  - `fun courtLines(): List<Pair<CourtPoint, CourtPoint>>` - empty unless complete; the 12 `CourtModel` search segments projected through the inverse of the marks' `homography()`; empty if the homography fails.
  - `CourtMarkingSpec` constants, verbatim:
    - `FINDING = "Finding the court..."`
    - `FOUND_NOTE = "Found the court automatically. Check the lines sit on the court's lines; tap a point to move it."`
    - `NOT_FOUND_NOTE = "Couldn't find the court. Tap the four corners and the rest will be placed for you."`
    - `ALIGNING = "Lining the points up with the court..."`
    - `ALIGNED_NOTE = "Lined up with the court's lines. Check them; tap a point to move it."`
    - `ALIGN_FAILED_NOTE = "Couldn't line the points up. Tap the rest."`
    - `FIND_COURT = "Find court"`
    - `fun moveInstruction(index: Int) = "Tap the new spot for ${fullLabels[index]}"`
  - `CourtAutoMarking.kt`: `enum class CourtFinderStatus { IDLE, FINDING, FOUND, NOT_FOUND, ALIGNING, ALIGNED, ALIGN_FAILED }` with `val note: String?` (FOUND, NOT_FOUND, ALIGNED, ALIGN_FAILED map to the notes above, others null) and `val busyLine: String?` (FINDING -> FINDING, ALIGNING -> ALIGNING); `fun CourtFinding.toCourtPoints(): List<CourtPoint>?` (table order, `Float`); and `fun applyFinding(state: CourtMarkingState, finding: CourtFinding): CourtMarkingState?` - null (discard) when `state.points` is not empty, else `state.withDetected(...)`, null when not found.
  - The analysis `CourtModel` is `internal`; expose what `courtLines()` needs through a public `val COURT_LINE_SEGMENTS: List<Pair<Point, Point>>` in `analysis/.../court/CourtFinder.kt` (the 12 search segments).

- [ ] **Step 1: Write the failing tests** (in `CourtMarkingModelTest.kt`):

```kotlin
@Test fun undo_reverses_a_move() {
    var s = complete()                         // helper: 12 points placed by place()
    val before = s.points
    s = s.tap(100f, 100f, 1920f, 1080f, 22f)   // select point 0 at (100,100) in a 1920x1080 display = source
    s.selected shouldBe 0
    s = s.tap(300f, 300f, 1920f, 1080f, 22f)
    s.points[0] shouldBe CourtPoint(300f, 300f)
    s.selected shouldBe null
    s.undo().points shouldBe before
}

@Test fun tapping_the_selected_point_again_cancels() {
    var s = complete().tap(100f, 100f, 1920f, 1080f, 22f)
    s = s.tap(105f, 102f, 1920f, 1080f, 22f)
    s.selected shouldBe null
    s.points shouldBe complete().points
}

@Test fun a_tap_away_from_every_point_selects_nothing() {
    complete().tap(1500f, 50f, 1920f, 1080f, 22f).selected shouldBe null
}

@Test fun a_detection_is_one_undo_step() {
    val empty = CourtMarkingState(1920, 1080, markedAtSeconds = 0.1, frameSeconds = 0.1)
    val s = empty.withDetected(List(12) { CourtPoint(it * 10f, it * 5f) })
    s.isComplete shouldBe true
    s.undo().points shouldBe emptyList()
}

@Test fun the_fourth_tap_triggers_completion_and_undo_does_not() {
    var s = CourtMarkingState(1920, 1080)
    repeat(3) { s = s.tap(10f + it, 10f, 1920f, 1080f, 22f) }
    val four = s.tap(50f, 50f, 1920f, 1080f, 22f)
    four.justReachedFourCorners(s) shouldBe true
    four.undo().justReachedFourCorners(four) shouldBe false
}

@Test fun court_lines_follow_the_marks() {
    val s = idealMarks()                       // helper: 12 points projected from a known camera
    s.courtLines().size shouldBe 12
}
```

and in `CourtAutoMarkingTest.kt`:

```kotlin
@Test fun a_finding_is_discarded_once_the_coach_has_tapped() {
    val tapped = CourtMarkingState(1920, 1080).tap(10f, 10f, 1920f, 1080f, 22f)
    applyFinding(tapped, foundFinding()) shouldBe null
}
@Test fun a_finding_fills_an_empty_marking_in_table_order() {
    val s = applyFinding(CourtMarkingState(1920, 1080), foundFinding())!!
    s.points.first() shouldBe CourtPoint(1f, 2f)   // foundFinding() puts topLeft at (1,2)
}
@Test fun not_found_changes_nothing() {
    applyFinding(CourtMarkingState(1920, 1080), CourtFinding(null, null, "no hypothesis", null, 5)) shouldBe null
}
```

- [ ] **Step 2: Run to verify they fail**: `./gradlew :shared:jvmTest --tests '*CourtMarkingModelTest*' --tests '*CourtAutoMarkingTest*'`.
- [ ] **Step 3: Implement.** Existing tests must keep passing: `undo` on a restored marking (no history) still drops the last point.
- [ ] **Step 4: Run** `./gradlew :shared:jvmTest :shared:iosSimulatorArm64Test`. Expected: PASS.
- [ ] **Step 5: Commit** `git commit -m "feat(shared): marking history, move a point, apply a found court"`

---

### Task 7: Android

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localvideo/court/CourtMarkingViewModel.kt`, `CourtMarkingScreen.kt`, `androidApp/src/main/java/com/badmintontracker/android/AuthGate.kt:590`
- Create: `androidApp/src/main/java/com/badmintontracker/android/localvideo/court/CourtPixels.kt`
- Test: `androidApp/src/test/java/com/badmintontracker/android/localvideo/court/CourtMarkingViewModelTest.kt` (extend)

**Interfaces:**
- Consumes: Task 6 (`tap`, `withDetected`, `justReachedFourCorners`, `applyFinding`, `CourtFinderStatus`, strings), `CourtFinder`.
- Produces:
  - `CourtPixels.kt`: `fun Bitmap.toRgbImage(): RgbImage` (`getPixels`), `class CourtFinderPort(val find: suspend (CourtFrame) -> CourtFinding, val fromCorners: suspend (CourtFrame, List<CourtPoint>) -> CourtFinding)` and `val DefaultCourtFinderPort` running `CourtFinder` on `Dispatchers.Default` (a frame without a bitmap returns not found).
  - `CourtMarkingViewModel(entryId, savedKeypoints, loadFrame, finder: CourtFinderPort = DefaultCourtFinderPort)`; `CourtMarkingUiState` gains `status: CourtFinderStatus = IDLE`.
  - VM behaviour: after the frame loads with no saved marks, set FINDING and run `finder.find`; apply with `applyFinding` (FOUND) or set NOT_FOUND (also on exception, logged with `Log.w("CourtFinder", ...)`); a finding discarded by `applyFinding` leaves the status at IDLE. `onTap(x, y, w, h, hitRadius)` calls `marking.tap`; when `justReachedFourCorners`, set ALIGNING and run `finder.fromCorners` with the four points; apply with `withDetected` only if the marking still has exactly those four points (ALIGNED), else leave it; not found -> ALIGN_FAILED. `onFindCourt()` runs `find` and applies with `withDetected` regardless of existing points (FOUND / NOT_FOUND). `onUndo`/`onClear` reset status to IDLE. Log each finding's `reason`, `residualPx`, `millis` at info level with tag `CourtFinder`.
- Screen: instruction row shows `status.busyLine` with a small `CircularProgressIndicator` while FINDING/ALIGNING, else `moveInstruction(selected)` when a point is selected, else today's text; the note (`status.note`) sits where the saved-marks note sits (saved-marks note wins when shown); the overlay draws `marking.courtLines()` in `MaterialTheme.colorScheme.primary`, 2 dp, when complete (replacing the corner rectangle then), and a ring (3 dp stroke, radius 16 dp / zoom, primary) on the selected point; buttons row: Undo, Clear, Find court (`CourtMarkingSpec.FIND_COURT`, disabled while FINDING or ALIGNING); taps pass `hitRadius = 22.dp.toPx() / scale` in layout pixels.

- [ ] **Step 1: Write the failing tests** (VM, fake port):

```kotlin
private fun p(i: Int) = com.badmintontracker.analysis.geometry.Point(100.0 + i * 50, 200.0 + i * 20)
private fun found12() = CourtFinding(
    com.badmintontracker.analysis.geometry.CourtKeypoints(
        p(0), p(1), p(2), p(3), p(4), p(5), p(6), p(7), p(8), p(9), p(10), p(11),
    ),
    null, "ok", 0.3, 12,
)
private fun notFound() = CourtFinding(null, null, "no hypothesis", null, 5)
```

```kotlin
@Test fun detection_fills_an_empty_marking() = runTest {
    val vm = vm(finder = CourtFinderPort(find = { found12() }, fromCorners = { _, _ -> notFound() }))
    vm.state.value.marking!!.isComplete shouldBe true
    vm.state.value.status shouldBe CourtFinderStatus.FOUND
}
@Test fun detection_after_a_tap_is_discarded() = runTest {
    val gate = CompletableDeferred<CourtFinding>()
    val vm = vm(finder = CourtFinderPort(find = { gate.await() }, fromCorners = { _, _ -> notFound() }))
    vm.onTap(10f, 10f, 1920f, 1080f, 22f)
    gate.complete(found12())
    vm.state.value.marking!!.points.size shouldBe 1
}
@Test fun a_failing_finder_falls_back_to_manual() = runTest {
    val vm = vm(finder = CourtFinderPort(find = { error("boom") }, fromCorners = { _, _ -> notFound() }))
    vm.state.value.status shouldBe CourtFinderStatus.NOT_FOUND
    vm.onTap(10f, 10f, 1920f, 1080f, 22f)
    vm.state.value.marking!!.points.size shouldBe 1
}
@Test fun the_fourth_corner_asks_for_completion() = runTest {
    var asked: List<CourtPoint>? = null
    val vm = vm(finder = CourtFinderPort(find = { notFound() }, fromCorners = { _, c -> asked = c; found12() }))
    listOf(10f to 10f, 1900f to 10f, 1900f to 1000f, 10f to 1000f).forEach { (x, y) -> vm.onTap(x, y, 1920f, 1080f, 22f) }
    asked!!.size shouldBe 4
    vm.state.value.marking!!.isComplete shouldBe true
    vm.state.value.status shouldBe CourtFinderStatus.ALIGNED
}
@Test fun saved_marks_do_not_run_the_finder() = runTest {
    var ran = false
    vm(savedKeypoints = savedKeypoints(), finder = CourtFinderPort(find = { ran = true; found12() }, fromCorners = { _, _ -> notFound() }))
    ran shouldBe false
}
```

- [ ] **Step 2: Run to verify they fail**: `./gradlew :androidApp:testDebugUnitTest --tests '*CourtMarkingViewModelTest*'`.
- [ ] **Step 3: Implement** the VM, `CourtPixels.kt`, the screen changes and `AuthGate.kt` (default port). Existing VM tests must still pass (they construct without a finder: the default port gets a `CourtFrame` whose `frame` is null and returns not found; make that path not throw).
- [ ] **Step 4: Run** `./gradlew :androidApp:testDebugUnitTest` and `./gradlew :androidApp:assembleDebug`. Expected: PASS / BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `git commit -m "feat(android): find the court on the marking screen, complete from four corners, move a point"`

---

### Task 8: iOS

**Files:**
- Modify: `shared/src/iosMain/kotlin/com/badmintontracker/shared/local/BulkInterop.kt`, `iosApp/Sources/CourtMarking/CourtMarkingView.swift`
- Create: `iosApp/Sources/CourtMarking/CourtFinderBridge.swift`, `iosApp/Tests/CourtFinderBridgeTests.swift`
- Test: `shared/src/iosTest/kotlin/com/badmintontracker/shared/local/BulkInteropTest.kt` (extend)

**Interfaces:**
- Consumes: Task 6, `CourtFinder`, `RgbImage`.
- Produces:
  - `BulkInterop.rgbImage(pixels: CPointer<ByteVar>, width: Int, height: Int, bytesPerRow: Int): RgbImage` (`@Throws(Throwable::class)`; reads RGBA8 rows, alpha ignored, into ARGB ints).
  - `enum CourtFinderBridge { static func find(_ image: CGImage) async -> CourtFinding; static func fromCorners(_ image: CGImage, corners: [CourtPoint]) async -> CourtFinding }` - draws the image into a `CGContext` (8 bits, RGBA, `premultipliedLast`, device RGB) of the image's own pixel size, calls `BulkInterop` inside `withUnsafeMutableBytes`, then `CourtFinder.shared.find(image:)` on `Task.detached(priority: .userInitiated)`. Logged through the existing `os.Logger` pattern (`LocalAnalysisRunner.systemLog`) with category `CourtFinder`.
  - `CourtMarkingView`: `@State private var finderStatus: CourtFinderStatus = .idle`; the frame's `CGImage` kept from `CourtFrameLoader` (add `cgImage` to `CourtFrame` if `image.cgImage` is not reliable); same behaviour as Android Task 7 through the same shared functions (`tap`, `applyFinding`, `justReachedFourCorners`, `withDetected`), same copy, overlay (`courtLines()` in `Shuttl.accent`, 2 pt) and selection ring; Find court button in the Undo/Clear `HStack`; the hit radius is `22 / scale` points in display space.
- [ ] **Step 1: Write the failing tests**: Kotlin `BulkInteropTest` - a 2x2 RGBA buffer `[255,0,0,255, 0,255,0,255, 0,0,255,255, 10,20,30,255]` with `bytesPerRow = 8` becomes ARGB `0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFF0A141E`; a `bytesPerRow = 12` buffer with padding reads the same. Swift `CourtFinderBridgeTests`: a 64x64 grey `CGImage` returns `found == false` without throwing; a rendered court (draw lines with Core Graphics: a white trapezoid court outline plus service and centre lines on green, 1280x720, from the same camera as `SyntheticCourt.camera(w: 1280, h: 720, f: 1000)`, using the 12 projected keypoints the test hardcodes from the Kotlin test's printout) is found.
- [ ] **Step 2: Run to verify they fail**: `./gradlew :shared:iosSimulatorArm64Test --tests '*BulkInteropTest*'`; iOS unit tests per Global Constraints.
- [ ] **Step 3: Implement**, `xcodegen generate`, confirm `CourtFinderBridge.o` exists under `iosApp/build/DerivedData`.
- [ ] **Step 4: Run** `./gradlew :shared:iosSimulatorArm64Test` and the iOS unit tests (grep `iosAppTests` and `iosAppUITests` counts). Expected: PASS, including `TypographyAdoptionTests` (no `.font(` outside the allowlist; use `.shuttlType`).
- [ ] **Step 5: Commit** `git commit -m "feat(ios): find the court on the marking screen, complete from four corners, move a point"`

---

### Task 9: On the emulator and the simulator, and the record

**Files:**
- Modify: `docs/plans/2026-09-27-court-auto-detect-design.md` (append §9 "What shipped differently" and §10 "Verification, as run")
- Create: `docs/screenshots/2026-09-27-court-detect-{android,ios}-{found,moved,corners}.png`

- [ ] **Step 1: Clips.** `ffmpeg -ss 0 -t 20 -i ~/Downloads/IMG_4795.MOV -c copy /tmp/hall-20s.mov`; `IMG_5892.MOV` is already in the emulator's `/sdcard/Movies` and the simulator's Photos. Push the hall clip (`adb push` + media scan; `xcrun simctl addmedia`).
- [ ] **Step 2: Android** (`ANDROID_SERIAL=emulator-5554`; emulator started with `-feature -HardwareDecoder -memory 6144` if not running; install with `adb install -r` of the fresh debug APK). For each clip: add it, reach the marking screen, screenshot after the court is found (`adb exec-out screencap -p`, capture to scratch, view, then copy); read `adb logcat -d -s CourtFinder` for the time; move one point (tap it, tap a new spot) and screenshot; Clear, tap four corners, screenshot the completion. The emulator session may need the user to sign in: if the app shows Sign in, stop and ask for it rather than entering credentials.
- [ ] **Step 3: iOS** (iPhone 17 Pro simulator, Release build as before). Same three screenshots for both clips; the time from the `CourtFinder` log (`xcrun simctl spawn booted log show --last 5m --predicate 'category == "CourtFinder"'`).
- [ ] **Step 4: Record** every number and every difference from the design in §9-§10, including the untested items of design §7.
- [ ] **Step 5: Full gates**: `./gradlew :analysis:jvmTest :analysis:iosSimulatorArm64Test :shared:jvmTest :shared:iosSimulatorArm64Test :androidApp:testDebugUnitTest`, `ANALYSIS_REQUIRE_CORPUS=1 ./gradlew :analysis:jvmTest --tests '*CourtCorpusTest*'`, the iOS unit and UI tests. All green; any skip explained.
- [ ] **Step 6: Commit** `git commit -m "docs: court detection as shipped, with emulator and simulator screenshots"`

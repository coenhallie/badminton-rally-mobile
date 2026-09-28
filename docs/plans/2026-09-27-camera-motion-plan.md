# Camera Motion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Follow a handheld camera through a recording, so every pose frame is mapped to the court through the court fit for that frame, on the phone (Android and iOS) and in the cloud worker.

**Architecture:** A pure-Kotlin anchored Lucas-Kanade tracker in `:analysis` estimates `M(t)`, the homography from the court-marking frame to each frame, at 10 Hz from a 480-wide luma image. `M` travels in `RawInference` version 2. `selectPlayers` maps each person into marking-frame pixels by `M⁻¹` and runs the unchanged selector. The skeleton store keeps `M` per pose so the Skeleton panel's court tiles use `H · M⁻¹`. The cloud runs the same algorithm through OpenCV, stabilises its in-loop decisions with it, and writes it into the pose file.

**Tech Stack:** Kotlin Multiplatform (`:analysis` pure, `:shared`), Jetpack Compose (Android), SwiftUI with SKIE (iOS), Python 3 + OpenCV + Modal (`badminton-tracker/backend`).

**Spec:** `docs/plans/2026-09-27-camera-motion-design.md`. Read it before any task. Section numbers below (§n) refer to it.

## Global Constraints

- Tracker long side 480 px; area-average downsample; pixel-centre mapping `S = [[sx, 0, 0.5 sx - 0.5], [0, sy, 0.5 sy - 0.5], [0, 0, 1]]`, `sx = W / w`.
- Corners: up to 150, quality 0.01, min distance 8 px, 3x3 block over Sobel gradients, 10 px border; fewer than 40 corners means give up (no camera, fixed marks).
- Pyramid: 3 levels, `[1 4 6 4 1] / 16` blur both directions with replicated border, then every second pixel; LK gradients are Scharr / 32.
- LK: 21x21 window, bilinear sampling with replicated border, max 20 iterations per level, stop when update length ≤ 0.03 px, fail when normalised min eigenvalue < `LK_MIN_EIGEN` or the point ends outside the image.
- RANSAC: 4-point DLT via `calculateHomography`, 1.0 px threshold at tracker scale, 300 iterations, `kotlin.random.Random(0x5EED)`, least-squares refit on inliers.
- Accept a sample when inliers ≥ 40, every marking-frame image corner moves ≤ 25% of the tracker width, and the normalised upper-left 2x2 determinant is in 0.5..2.
- Sampling: every 0.1 s timestamp bucket, plus the marking frame (first frame with timestamp ≥ `T - 1e-6`). `T = marked_at_s ?: legacy`, legacy is 0.1 except iOS local entries (generator `actualTime`).
- `at(t)`: exact within 1e-6 s; else blend neighbours ≤ 0.5 s apart; else nearest within 0.15 s; else null. Every stored `M` has `h22 == 1.0`.
- `CAMERA_LOST` is appended after `BAD_COURT` in `RejectionReason`.
- RawInference v2 = v1 + `hasCamera i8` [+ `referenceTimestamp f64`, `count i32`, count × (`timestamp f64`, 8 × `f64`)]. Decode reads v1 and v2 and rejects trailing bytes; encode writes v2.
- Skeleton store v4 = v3 layout + `hasCamera i32` after the marks; when 1, each pose record is followed by 8 × `f64`. Both `save` and `saveAll` write v4; v1..v3 still load.
- Never use the em dash in prose, comments, commit messages or docs. No `Co-Authored-By` trailer and no "Generated with" footer.
- Swift call sites pass every argument (SKIE default arguments are off).
- Build/test commands: `./gradlew :analysis:jvmTest`, `./gradlew :analysis:iosSimulatorArm64Test`, `./gradlew :shared:jvmTest` (or the module's existing JVM test task, see `shared/build.gradle.kts`), `./gradlew :androidApp:testDebugUnitTest`, iOS per `memory ios-build-environment` (xcodebuild test on the iPhone 17 Pro simulator). JAVA_HOME must be Android Studio's JBR.
- Pin `ANDROID_SERIAL=emulator-5554` for any install; the phone may be attached.

## Review Focus

1. **Portrait or odd-sized sources** (1080x1920, 1280x720, 3840x2160, a source already under 480): the downsample and `S` must hold for any size and orientation; the tracker must not assume 16:9 or landscape. Pinned in Task 1 and Task 4.
2. **A marking time later than 0.1 s** (`T = 1.0`, iOS legacy marks): pre-reference frames buffered and tracked backwards, nothing lost before `T`. Pinned in Task 4.
3. **Variable frame rate and duplicate timestamps** (the cloud's first two frames both read 0.0 s): sampling, the marking frame and `at()` must stay correct. Pinned in Task 4.
4. **A frame the camera tracker lost amid a good track**: the frame must be `CAMERA_LOST`, never mapped through the fixed marks, and a file with no camera section must behave exactly as today. Pinned in Task 7.
5. **Old files on a phone after update** (RawInference v1 from the deployed cloud, skeleton v1..v3): all still load and draw. Pinned in Task 6 and Task 11.

---

## File Structure

`badminton-rally-mobile`:

- `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/camera/GreyImage.kt` (create): the tracker's image type, bilinear sampling.
- `.../camera/Luma.kt` (create): tracker size and the area-average downsample.
- `.../camera/Pyramid.kt` (create): pyrDown, Scharr and Sobel gradients.
- `.../camera/Corners.kt` (create): Shi-Tomasi selection.
- `.../camera/LucasKanade.kt` (create): the anchored pyramidal LK with cached templates.
- `.../camera/Ransac.kt` (create): RANSAC homography.
- `.../camera/CameraMotion.kt` (create): `CameraSample`, `CameraMotion`, `at()`.
- `.../camera/CameraMotionTracker.kt` (create): sampling, reference, pending, acceptance, scaling.
- `.../geometry/Homography.kt` (modify): public `multiply3x3`, `invert3x3`, new `courtFitAt`.
- `.../raw/RawInference.kt`, `.../raw/RawInferenceCodec.kt` (modify): `camera`, v2.
- `.../player/NearPlayer.kt`, `.../player/PlayerTrack.kt` (modify): `CAMERA_LOST`, camera-aware `selectPlayers`, `PlayerPose.camera`.
- `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/camera/*Test.kt` (create): unit tests.
- `analysis/src/jvmTest/kotlin/com/badmintontracker/analysis/camera/CameraMotionCorpusTest.kt` (create): the real-video test, corpus-gated.
- `tools/models/camera_motion_probe.py` (create): writes the corpus inputs for that test.
- `shared/.../model/CourtKeypoints.kt`, `shared/.../localvideo/court/CourtMarkingModel.kt`, `shared/.../local/LocalInferenceEngine.kt`, `shared/.../local/LocalAnalysisCoordinator.kt`, `shared/.../local/CloudPose.kt`, `shared/.../local/CloudPoseCoordinator.kt` (modify).
- `shared/src/iosMain/.../local/BulkInterop.kt` (modify): `lumaDownsample`.
- Android: `localanalysis/AndroidLocalInferenceEngine.kt`, `localanalysis/SkeletonStore.kt`, `localanalysis/SkeletonPanel.kt`, `localvideo/court/CourtMarkingScreen.kt` / `CourtMarkingViewModel.kt`, `localanalysis/LocalAnalysisRunner.kt` (modify).
- iOS: `LocalAnalysis/IosLocalInferenceEngine.swift`, `LocalAnalysis/SkeletonStore.swift`, `LocalAnalysis/SkeletonPanel.swift`, `LocalVideo/CourtFrameLoader.swift` (or wherever it lives), `LocalVideo/CourtMarkingView.swift`, `LocalAnalysis/LocalAnalysisRunner.swift` (modify).

`badminton-tracker` (branch `feat/camera-motion`):

- `backend/camera_motion.py` (create), `backend/scripts/verify_camera_motion.py` (create).
- `backend/raw_inference_codec.py`, `backend/scripts/verify_raw_inference_codec.py` (modify): v2.
- `backend/modal_supabase_processor.py` (modify): image mount, shadows, shuttle area, plumbing.
- `backend/scripts/run_phase2_locally.py` (create): the §8.6 harness.

---

### Task 1: Matrix helpers, GreyImage and the luma downsample

**Files:**
- Modify: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/geometry/Homography.kt:195-230` (the private `multiply3x3`, `invert3x3`)
- Create: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/camera/GreyImage.kt`
- Create: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/camera/Luma.kt`
- Test: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/camera/LumaTest.kt`, `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/geometry/HomographyTest.kt` (add cases)

**Interfaces:**
- Produces: `fun multiply3x3(a: Matrix3x3, b: Matrix3x3): Matrix3x3`, `fun invert3x3(m: Matrix3x3): Matrix3x3?`, `fun courtFitAt(h: Matrix3x3, camera: Matrix3x3?): Matrix3x3?`; `class GreyImage(width: Int, height: Int, pixels: FloatArray)` with `sample(x: Double, y: Double): Double`; `object Luma { const val TRACKER_LONG_SIDE = 480; fun trackerSize(width: Int, height: Int): Pair<Int, Int>; fun downsample(plane: ByteArray, offset: Int, width: Int, height: Int, rowStride: Int, outWidth: Int, outHeight: Int): GreyImage; inline fun downsampleWith(width: Int, height: Int, outWidth: Int, outHeight: Int, read: (x: Int, y: Int) -> Int): GreyImage; fun trackerToSource(width: Int, height: Int, trackerWidth: Int, trackerHeight: Int): Matrix3x3 }`

- [ ] **Step 1: Write the failing tests**

`LumaTest.kt`:

```kotlin
package com.badmintontracker.analysis.camera

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LumaTest {
    @Test fun a_16_by_9_source_is_480_by_270() {
        Luma.trackerSize(1920, 1080) shouldBe (480 to 270)
        Luma.trackerSize(3840, 2160) shouldBe (480 to 270)
        Luma.trackerSize(1280, 720) shouldBe (480 to 270)
    }

    @Test fun a_portrait_source_keeps_its_long_side_vertical() {
        Luma.trackerSize(1080, 1920) shouldBe (270 to 480)
    }

    @Test fun a_source_already_small_is_used_as_is() {
        Luma.trackerSize(320, 240) shouldBe (320 to 240)
    }

    @Test fun an_integer_ratio_averages_whole_blocks() {
        // 4x2 source, rows padded to stride 6, down to 2x1: each output pixel is a 2x2 block.
        val stride = 6
        val plane = ByteArray(stride * 2)
        val values = arrayOf(intArrayOf(10, 20, 200, 250), intArrayOf(30, 40, 255, 255))
        for (y in 0 until 2) for (x in 0 until 4) plane[y * stride + x] = values[y][x].toByte()
        val out = Luma.downsample(plane, 0, 4, 2, stride, 2, 1)
        out.width shouldBe 2
        out.pixels[0].toDouble() shouldBe (25.0 plusOrMinus 1e-6)
        out.pixels[1].toDouble() shouldBe (240.0 plusOrMinus 1e-6)
    }

    @Test fun a_non_integer_ratio_covers_every_source_pixel_once() {
        // 5 wide to 2 wide: columns 0..1 then 2..4.
        val plane = byteArrayOf(0, 10, 20, 30, 40)
        val out = Luma.downsample(plane, 0, 5, 1, 5, 2, 1)
        out.pixels[0].toDouble() shouldBe (5.0 plusOrMinus 1e-6)
        out.pixels[1].toDouble() shouldBe (30.0 plusOrMinus 1e-6)
    }

    @Test fun bytes_above_127_read_unsigned() {
        val out = Luma.downsample(byteArrayOf(0xFF.toByte()), 0, 1, 1, 1, 1, 1)
        out.pixels[0].toDouble() shouldBe (255.0 plusOrMinus 1e-6)
    }

    @Test fun tracker_pixel_centres_map_to_source_pixel_centres() {
        val s = Luma.trackerToSource(1920, 1080, 480, 270)
        // Tracker pixel 0 covers source pixels 0..3, whose centre is 1.5.
        s[0][0] * 0.0 + s[0][2] shouldBe (1.5 plusOrMinus 1e-9)
        s[1][1] * 0.0 + s[1][2] shouldBe (1.5 plusOrMinus 1e-9)
        s[0][0] shouldBe (4.0 plusOrMinus 1e-9)
    }

    @Test fun bilinear_sampling_interpolates_and_replicates_the_border() {
        val img = GreyImage(2, 2, floatArrayOf(0f, 10f, 20f, 30f))
        img.sample(0.5, 0.5) shouldBe (15.0 plusOrMinus 1e-9)
        img.sample(-3.0, -3.0) shouldBe (0.0 plusOrMinus 1e-9)
        img.sample(9.0, 9.0) shouldBe (30.0 plusOrMinus 1e-9)
    }
}
```

Add to `HomographyTest.kt`:

```kotlin
    @Test fun court_fit_at_a_camera_is_h_times_the_cameras_inverse() {
        val h = listOf(listOf(0.01, 0.0, 0.0), listOf(0.0, 0.02, 0.0), listOf(0.0, 0.0, 1.0))
        val shift = listOf(listOf(1.0, 0.0, 30.0), listOf(0.0, 1.0, -12.0), listOf(0.0, 0.0, 1.0))
        val fit = courtFitAt(h, shift)!!
        // The marking-frame pixel (100, 50) is at (130, 38) in this frame; both must land on the same court point.
        val a = h.apply(100.0, 50.0)!!
        val b = fit.apply(130.0, 38.0)!!
        b.x shouldBe (a.x plusOrMinus 1e-9)
        b.y shouldBe (a.y plusOrMinus 1e-9)
        courtFitAt(h, null) shouldBe h
    }
```

- [ ] **Step 2: Run to see them fail**

Run: `./gradlew :analysis:jvmTest --tests '*LumaTest*' --tests '*HomographyTest*'`
Expected: compilation failure (unresolved `Luma`, `GreyImage`, `courtFitAt`).

- [ ] **Step 3: Implement**

`GreyImage.kt`:

```kotlin
package com.badmintontracker.analysis.camera

import kotlin.math.floor

/**
 * A greyscale image for the camera tracker, row-major, one grey level
 * (0..255, not normalised) per pixel.
 *
 * Floats rather than bytes because every stage after the downsample
 * (blur, gradients, bilinear sampling) produces fractions, and keeping one
 * type avoids a conversion at each of them.
 */
class GreyImage(val width: Int, val height: Int, val pixels: FloatArray) {
    init {
        require(width >= 1 && height >= 1) { "empty image $width x $height" }
        require(pixels.size == width * height) { "${pixels.size} pixels for $width x $height" }
    }

    /** Bilinear sample at a sub-pixel position, the border replicated outward. */
    fun sample(x: Double, y: Double): Double {
        val cx = x.coerceIn(0.0, (width - 1).toDouble())
        val cy = y.coerceIn(0.0, (height - 1).toDouble())
        val x0 = floor(cx).toInt().coerceAtMost(width - 1)
        val y0 = floor(cy).toInt().coerceAtMost(height - 1)
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fx = cx - x0
        val fy = cy - y0
        val a = pixels[y0 * width + x0]
        val b = pixels[y0 * width + x1]
        val c = pixels[y1 * width + x0]
        val d = pixels[y1 * width + x1]
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }
}
```

`Luma.kt`:

```kotlin
package com.badmintontracker.analysis.camera

import com.badmintontracker.analysis.geometry.Matrix3x3
import kotlin.math.max

/**
 * The camera tracker's input: the luma plane, area-averaged down so its long
 * side is [TRACKER_LONG_SIDE]. Design §4.1.
 *
 * Luma, not RGB: the tracker needs texture, the Y plane is already decoded on
 * both phones, and reading one plane is a third of the work of three.
 */
object Luma {
    const val TRACKER_LONG_SIDE = 480

    /** The tracker's image size for a source of this size. Orientation is kept. */
    fun trackerSize(width: Int, height: Int): Pair<Int, Int> {
        require(width >= 1 && height >= 1)
        val long = max(width, height)
        if (long <= TRACKER_LONG_SIDE) return width to height
        return if (width >= height) {
            TRACKER_LONG_SIDE to max(1, (height.toLong() * TRACKER_LONG_SIDE / width).toInt())
        } else {
            max(1, (width.toLong() * TRACKER_LONG_SIDE / height).toInt()) to TRACKER_LONG_SIDE
        }
    }

    /**
     * Area average of an 8-bit plane. Row `y` starts at `offset + y * rowStride`;
     * bytes read unsigned.
     */
    fun downsample(
        plane: ByteArray,
        offset: Int,
        width: Int,
        height: Int,
        rowStride: Int,
        outWidth: Int,
        outHeight: Int,
    ): GreyImage = downsampleWith(width, height, outWidth, outHeight) { x, y ->
        plane[offset + y * rowStride + x].toInt() and 0xFF
    }

    /**
     * The same average over any pixel source, for a caller whose plane is not a
     * ByteArray (iOS reads a locked CVPixelBuffer through a pointer).
     *
     * Output pixel `(ox, oy)` averages source columns
     * `floor(ox * W / w) until max(that + 1, floor((ox + 1) * W / w))`, and rows
     * likewise, so every source pixel counts once and a non-integer ratio still
     * averages whole pixels.
     */
    inline fun downsampleWith(
        width: Int,
        height: Int,
        outWidth: Int,
        outHeight: Int,
        read: (x: Int, y: Int) -> Int,
    ): GreyImage {
        val x0 = IntArray(outWidth) { (it.toLong() * width / outWidth).toInt() }
        val x1 = IntArray(outWidth) { max(x0[it] + 1, ((it + 1).toLong() * width / outWidth).toInt()) }
        val out = FloatArray(outWidth * outHeight)
        for (oy in 0 until outHeight) {
            val y0 = (oy.toLong() * height / outHeight).toInt()
            val y1 = max(y0 + 1, ((oy + 1).toLong() * height / outHeight).toInt())
            for (ox in 0 until outWidth) {
                var sum = 0L
                for (y in y0 until y1) for (x in x0[ox] until x1[ox]) sum += read(x, y)
                out[oy * outWidth + ox] = sum.toFloat() / ((y1 - y0) * (x1[ox] - x0[ox]))
            }
        }
        return GreyImage(outWidth, outHeight, out)
    }

    /**
     * Tracker pixels to source pixels, by pixel centres:
     * `x_src = (x_t + 0.5) * sx - 0.5`. Design §4.1.
     */
    fun trackerToSource(width: Int, height: Int, trackerWidth: Int, trackerHeight: Int): Matrix3x3 {
        val sx = width.toDouble() / trackerWidth
        val sy = height.toDouble() / trackerHeight
        return listOf(
            listOf(sx, 0.0, 0.5 * sx - 0.5),
            listOf(0.0, sy, 0.5 * sy - 0.5),
            listOf(0.0, 0.0, 1.0),
        )
    }
}
```

In `Homography.kt`: change `private fun multiply3x3` and `private fun invert3x3` to public (keep names and bodies; update their KDoc to say they are public for the camera tracker and the Skeleton panel), and add:

```kotlin
/**
 * The court fit for a frame the camera had moved in: [h] fits the marking
 * frame, and [camera] maps marking-frame pixels to this frame's pixels, so a
 * pixel here reaches the court through `h · camera⁻¹`. Null [camera] means the
 * camera is not being followed, and the fit is [h] itself. Design §5.3.
 */
fun courtFitAt(h: Matrix3x3, camera: Matrix3x3?): Matrix3x3? {
    if (camera == null) return h
    val inverse = invert3x3(camera) ?: return null
    return multiply3x3(h, inverse)
}
```

- [ ] **Step 4: Run to see them pass**

Run: `./gradlew :analysis:jvmTest --tests '*LumaTest*' --tests '*HomographyTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add analysis/src/commonMain/kotlin/com/badmintontracker/analysis/camera analysis/src/commonMain/kotlin/com/badmintontracker/analysis/geometry/Homography.kt analysis/src/commonTest/kotlin/com/badmintontracker/analysis/camera analysis/src/commonTest/kotlin/com/badmintontracker/analysis/geometry/HomographyTest.kt
git commit -m "feat(analysis): the camera tracker's image, its luma downsample, and a court fit per frame"
```

---

### Task 2: Pyramid, gradients and Shi-Tomasi corners

**Files:**
- Create: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/camera/Pyramid.kt`, `.../camera/Corners.kt`
- Test: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/camera/PyramidTest.kt`, `.../camera/CornersTest.kt`, plus `.../camera/Synthetic.kt` (test helper, shared by later tasks)

**Interfaces:**
- Consumes: `GreyImage` (Task 1).
- Produces: `internal fun pyrDown(src: GreyImage): GreyImage`; `internal fun pyramid(base: GreyImage, levels: Int): List<GreyImage>`; `internal class Gradients(val gx: FloatArray, val gy: FloatArray)`; `internal fun scharr(img: GreyImage): Gradients` (divided by 32); `internal fun shiTomasi(img: GreyImage, maxCorners: Int = 150, quality: Double = 0.01, minDistance: Double = 8.0, border: Int = 10): List<Pair<Double, Double>>`. Test helper `object Synthetic { fun texture(width: Int, height: Int, seed: Int): GreyImage; fun warp(src: GreyImage, m: Matrix3x3): GreyImage }` where `warp` produces `dst(p) = src(m⁻¹ p)` by bilinear sampling (so `m` maps src pixels to dst pixels).

- [ ] **Step 1: Write the failing tests**

`Synthetic.kt` (commonTest):

```kotlin
package com.badmintontracker.analysis.camera

import com.badmintontracker.analysis.geometry.Matrix3x3
import com.badmintontracker.analysis.geometry.apply
import com.badmintontracker.analysis.geometry.invert3x3
import kotlin.math.sin
import kotlin.random.Random

/** Test images with texture a tracker can hold on to, and known warps of them. */
object Synthetic {
    /** Smooth blobs plus a little noise: corners everywhere, no aliasing. */
    fun texture(width: Int, height: Int, seed: Int): GreyImage {
        val r = Random(seed)
        val blobs = List(60) { Triple(r.nextDouble() * width, r.nextDouble() * height, 4.0 + r.nextDouble() * 10) }
        val px = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            var v = 128.0 + 30 * sin(x * 0.11) * sin(y * 0.07)
            for ((bx, by, s) in blobs) {
                val d2 = (x - bx) * (x - bx) + (y - by) * (y - by)
                v += 90 * kotlin.math.exp(-d2 / (2 * s * s)) * (if ((bx + by).toInt() % 2 == 0) 1 else -1)
            }
            px[y * width + x] = v.coerceIn(0.0, 255.0).toFloat()
        }
        return GreyImage(width, height, px)
    }

    /** `dst(p) = src(m⁻¹ p)`: [m] maps src pixels to dst pixels. */
    fun warp(src: GreyImage, m: Matrix3x3): GreyImage {
        val inv = invert3x3(m)!!
        val px = FloatArray(src.width * src.height)
        for (y in 0 until src.height) for (x in 0 until src.width) {
            val s = inv.apply(x.toDouble(), y.toDouble())!!
            px[y * src.width + x] = src.sample(s.x, s.y).toFloat()
        }
        return GreyImage(src.width, src.height, px)
    }
}
```

`PyramidTest.kt`:

```kotlin
package com.badmintontracker.analysis.camera

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class PyramidTest {
    @Test fun each_level_halves_rounding_up() {
        val levels = pyramid(Synthetic.texture(481, 271, 1), 3)
        levels.map { it.width to it.height } shouldBe listOf(481 to 271, 241 to 136, 121 to 68)
    }

    @Test fun a_flat_image_stays_flat() {
        val flat = GreyImage(8, 8, FloatArray(64) { 77f })
        pyrDown(flat).pixels.all { it == 77f } shouldBe true
    }

    @Test fun scharr_of_a_ramp_is_its_slope() {
        val ramp = GreyImage(10, 10, FloatArray(100) { (it % 10) * 3f })
        val g = scharr(ramp)
        g.gx[5 * 10 + 5].toDouble() shouldBe (3.0 plusOrMinus 1e-5)
        g.gy[5 * 10 + 5].toDouble() shouldBe (0.0 plusOrMinus 1e-5)
    }
}
```

`CornersTest.kt`:

```kotlin
package com.badmintontracker.analysis.camera

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.math.hypot
import kotlin.test.Test

class CornersTest {
    @Test fun a_textured_image_yields_the_requested_count() {
        shiTomasi(Synthetic.texture(480, 270, 2), maxCorners = 150) shouldHaveSize 150
    }

    @Test fun corners_keep_their_distance_and_the_border() {
        val c = shiTomasi(Synthetic.texture(480, 270, 3))
        for (i in c.indices) for (j in i + 1 until c.size) {
            (hypot(c[i].first - c[j].first, c[i].second - c[j].second) >= 8.0) shouldBe true
        }
        c.all { it.first >= 10 && it.second >= 10 && it.first < 470 && it.second < 260 } shouldBe true
    }

    @Test fun a_flat_image_has_none() {
        shiTomasi(GreyImage(100, 100, FloatArray(10_000) { 50f })) shouldHaveSize 0
    }

    @Test fun a_single_square_gives_its_four_corners_first() {
        val px = FloatArray(100 * 100)
        for (y in 40 until 60) for (x in 40 until 60) px[y * 100 + x] = 200f
        val c = shiTomasi(GreyImage(100, 100, px), maxCorners = 4)
        c.size shouldBeGreaterThanOrEqual 4
        // Each found corner sits within 2 px of one of the square's corners.
        val truth = listOf(39.5 to 39.5, 59.5 to 39.5, 39.5 to 59.5, 59.5 to 59.5)
        c.all { p -> truth.any { hypot(p.first - it.first, p.second - it.second) <= 2.0 } } shouldBe true
    }
}
```

- [ ] **Step 2: Run to see them fail**

Run: `./gradlew :analysis:jvmTest --tests '*PyramidTest*' --tests '*CornersTest*'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

`Pyramid.kt`:

```kotlin
package com.badmintontracker.analysis.camera

/** Gradient images, one float per pixel, in grey levels per pixel. */
internal class Gradients(val gx: FloatArray, val gy: FloatArray)

/**
 * Blur with `[1 4 6 4 1] / 16` in both directions (border replicated) and
 * keep every second pixel. The output is `(w + 1) / 2` by `(h + 1) / 2`, and
 * output pixel `(x, y)` sits at input pixel `(2x, 2y)`, so a coordinate
 * halves exactly from one level to the next. Design §4.3 step 2.
 */
internal fun pyrDown(src: GreyImage): GreyImage {
    val w = src.width
    val h = src.height
    val p = src.pixels
    val tmp = FloatArray(w * h)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            fun at(xx: Int) = p[row + xx.coerceIn(0, w - 1)]
            tmp[row + x] = (at(x - 2) + 4 * at(x - 1) + 6 * at(x) + 4 * at(x + 1) + at(x + 2)) / 16f
        }
    }
    val ow = (w + 1) / 2
    val oh = (h + 1) / 2
    val out = FloatArray(ow * oh)
    for (oy in 0 until oh) {
        val y = oy * 2
        for (ox in 0 until ow) {
            val x = ox * 2
            fun at(yy: Int) = tmp[yy.coerceIn(0, h - 1) * w + x]
            out[oy * ow + ox] = (at(y - 2) + 4 * at(y - 1) + 6 * at(y) + 4 * at(y + 1) + at(y + 2)) / 16f
        }
    }
    return GreyImage(ow, oh, out)
}

/** [levels] images, the first being [base]. */
internal fun pyramid(base: GreyImage, levels: Int): List<GreyImage> {
    val out = ArrayList<GreyImage>(levels)
    out.add(base)
    while (out.size < levels) out.add(pyrDown(out.last()))
    return out
}

/** Scharr derivatives divided by 32, border replicated: a ramp of slope s reads s. */
internal fun scharr(img: GreyImage): Gradients {
    val w = img.width
    val h = img.height
    val p = img.pixels
    fun at(x: Int, y: Int) = p[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
    val gx = FloatArray(w * h)
    val gy = FloatArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        gx[y * w + x] = (3 * (at(x + 1, y - 1) - at(x - 1, y - 1)) + 10 * (at(x + 1, y) - at(x - 1, y)) +
            3 * (at(x + 1, y + 1) - at(x - 1, y + 1))) / 32f
        gy[y * w + x] = (3 * (at(x - 1, y + 1) - at(x - 1, y - 1)) + 10 * (at(x, y + 1) - at(x, y - 1)) +
            3 * (at(x + 1, y + 1) - at(x + 1, y - 1))) / 32f
    }
    return Gradients(gx, gy)
}

/** Sobel derivatives (unscaled), border replicated, for corner selection. */
internal fun sobel(img: GreyImage): Gradients {
    val w = img.width
    val h = img.height
    val p = img.pixels
    fun at(x: Int, y: Int) = p[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
    val gx = FloatArray(w * h)
    val gy = FloatArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        gx[y * w + x] = (at(x + 1, y - 1) - at(x - 1, y - 1)) + 2 * (at(x + 1, y) - at(x - 1, y)) +
            (at(x + 1, y + 1) - at(x - 1, y + 1))
        gy[y * w + x] = (at(x - 1, y + 1) - at(x - 1, y - 1)) + 2 * (at(x, y + 1) - at(x, y - 1)) +
            (at(x + 1, y + 1) - at(x + 1, y - 1))
    }
    return Gradients(gx, gy)
}
```

`Corners.kt`:

```kotlin
package com.badmintontracker.analysis.camera

import kotlin.math.sqrt

/**
 * Shi-Tomasi corners, as OpenCV's `goodFeaturesToTrack(img, maxCorners,
 * quality, minDistance, blockSize = 3)` selects them. Design §4.3 step 1.
 *
 * The response is the smaller eigenvalue of the structure tensor summed over
 * a 3x3 block of Sobel gradients. A pixel is a candidate when its response is
 * at least [quality] times the image's strongest, it is the maximum of its
 * 3x3 neighbourhood, and it lies [border] pixels or more inside the image.
 * Candidates are taken strongest first, skipping any closer than
 * [minDistance] to one already taken.
 */
internal fun shiTomasi(
    img: GreyImage,
    maxCorners: Int = 150,
    quality: Double = 0.01,
    minDistance: Double = 8.0,
    border: Int = 10,
): List<Pair<Double, Double>> {
    val w = img.width
    val h = img.height
    val g = sobel(img)
    val xx = FloatArray(w * h)
    val xy = FloatArray(w * h)
    val yy = FloatArray(w * h)
    for (i in 0 until w * h) {
        xx[i] = g.gx[i] * g.gx[i]
        xy[i] = g.gx[i] * g.gy[i]
        yy[i] = g.gy[i] * g.gy[i]
    }
    val response = FloatArray(w * h)
    var strongest = 0f
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
        var a = 0f
        var b = 0f
        var c = 0f
        for (dy in -1..1) for (dx in -1..1) {
            val i = (y + dy) * w + (x + dx)
            a += xx[i]; b += xy[i]; c += yy[i]
        }
        val half = (a + c) / 2f
        val disc = sqrt(((a - c) / 2f) * ((a - c) / 2f) + b * b)
        val r = half - disc
        response[y * w + x] = r
        if (r > strongest) strongest = r
    }
    if (strongest <= 0f) return emptyList()
    val threshold = (quality * strongest).toFloat()
    val candidates = ArrayList<Int>()
    for (y in maxOf(border, 1) until minOf(h - border, h - 1)) for (x in maxOf(border, 1) until minOf(w - border, w - 1)) {
        val r = response[y * w + x]
        if (r < threshold || r <= 0f) continue
        var isMax = true
        for (dy in -1..1) for (dx in -1..1) if (response[(y + dy) * w + (x + dx)] > r) isMax = false
        if (isMax) candidates.add(y * w + x)
    }
    // Strongest first; ties broken by position so every platform picks the same set.
    candidates.sortWith(compareByDescending<Int> { response[it] }.thenBy { it })
    val taken = ArrayList<Pair<Double, Double>>()
    val min2 = minDistance * minDistance
    for (i in candidates) {
        if (taken.size >= maxCorners) break
        val x = (i % w).toDouble()
        val y = (i / w).toDouble()
        if (taken.all { (tx, ty) -> (tx - x) * (tx - x) + (ty - y) * (ty - y) >= min2 }) taken.add(x to y)
    }
    return taken
}
```

- [ ] **Step 4: Run to see them pass**

Run: `./gradlew :analysis:jvmTest --tests '*PyramidTest*' --tests '*CornersTest*'`
Expected: PASS. If `a_single_square_gives_its_four_corners_first` finds the corner response at integer pixels 39/40 or 59/60, the 2 px tolerance covers it; do not loosen it further.

- [ ] **Step 5: Commit**

```bash
git add analysis/src/commonMain/kotlin/com/badmintontracker/analysis/camera analysis/src/commonTest/kotlin/com/badmintontracker/analysis/camera
git commit -m "feat(analysis): pyramids, gradients and corners for the camera tracker"
```

---

### Task 3: Anchored pyramidal Lucas-Kanade and RANSAC

**Files:**
- Create: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/camera/LucasKanade.kt`, `.../camera/Ransac.kt`
- Test: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/camera/LucasKanadeTest.kt`, `.../camera/RansacTest.kt`

**Interfaces:**
- Consumes: `GreyImage`, `pyramid`, `scharr` (Tasks 1-2), `calculateHomography`, `apply` (existing `geometry`).
- Produces:
  - `internal class AnchoredTemplates(reference: List<GreyImage>, points: List<Pair<Double, Double>>)` which precomputes, per point and per level, the 21x21 template values, gradients and the structure tensor, and exposes `val points`.
  - `internal fun AnchoredTemplates.track(target: List<GreyImage>, guesses: DoubleArray): Pair<DoubleArray, BooleanArray>` where `guesses` holds `x0, y0, x1, y1, ...` (initial positions in the target at level 0) and the result is the tracked positions and a per-point success flag.
  - `internal class RansacResult(val homography: Matrix3x3, val inliers: Int)`; `internal fun ransacHomography(src: List<Point>, dst: List<Point>, threshold: Double = 1.0, iterations: Int = 300, random: Random = Random(0x5EED)): RansacResult?`.
  - Constants in `LucasKanade.kt`: `internal const val LK_WINDOW_HALF = 10`, `internal const val LK_MAX_ITERATIONS = 20`, `internal const val LK_EPSILON = 0.03`, `internal const val LK_MIN_EIGEN = 1e-3`, `internal const val PYRAMID_LEVELS = 3`.

`LK_MIN_EIGEN` is in this implementation's units (gradients in grey levels per pixel, divided by the 441-pixel window area). It is not OpenCV's 1e-4, whose derivative scaling differs. 1e-3 rejects a window flatter than about one grey level of gradient; Step 4 checks it keeps every corner Shi-Tomasi picks on the synthetic texture.

- [ ] **Step 1: Write the failing tests**

`LucasKanadeTest.kt`:

```kotlin
package com.badmintontracker.analysis.camera

import com.badmintontracker.analysis.geometry.apply
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import kotlin.math.hypot
import kotlin.test.Test

class LucasKanadeTest {
    private val ref = Synthetic.texture(480, 270, 7)
    private val refPyr = pyramid(ref, PYRAMID_LEVELS)
    private val corners = shiTomasi(ref)
    private val templates = AnchoredTemplates(refPyr, corners)

    private fun trackUnder(m: List<List<Double>>, guessFromIdentity: Boolean): Pair<Int, Double> {
        val target = pyramid(Synthetic.warp(ref, m), PYRAMID_LEVELS)
        val guesses = DoubleArray(corners.size * 2)
        corners.forEachIndexed { i, (x, y) ->
            val g = if (guessFromIdentity) (x to y) else m.apply(x, y)!!.let { it.x to it.y }
            guesses[2 * i] = g.first; guesses[2 * i + 1] = g.second
        }
        val (pos, ok) = templates.track(target, guesses)
        var good = 0
        var worst = 0.0
        corners.forEachIndexed { i, (x, y) ->
            if (!ok[i]) return@forEachIndexed
            val truth = m.apply(x, y)!!
            if (truth.x < 12 || truth.y < 12 || truth.x > 468 || truth.y > 258) return@forEachIndexed
            good++
            worst = maxOf(worst, hypot(pos[2 * i] - truth.x, pos[2 * i + 1] - truth.y))
        }
        return good to worst
    }

    @Test fun a_shift_of_six_pixels_is_found_from_no_guess() {
        val (good, worst) = trackUnder(listOf(listOf(1.0, 0.0, 6.0), listOf(0.0, 1.0, -4.0), listOf(0.0, 0.0, 1.0)), true)
        good shouldBeGreaterThan 120
        worst shouldBeLessThan 0.1
    }

    @Test fun a_thirty_pixel_pan_is_found_from_no_guess_through_the_pyramid() {
        val (good, worst) = trackUnder(listOf(listOf(1.0, 0.0, 30.0), listOf(0.0, 1.0, 12.0), listOf(0.0, 0.0, 1.0)), true)
        good shouldBeGreaterThan 100
        worst shouldBeLessThan 0.1
    }

    @Test fun a_rotation_with_perspective_is_found_from_a_close_guess() {
        val m = listOf(listOf(0.995, -0.03, 14.0), listOf(0.028, 1.002, -9.0), listOf(2e-5, -1e-5, 1.0))
        val (good, worst) = trackUnder(m, false)
        good shouldBeGreaterThan 120
        worst shouldBeLessThan 0.1
    }
}
```

`RansacTest.kt`:

```kotlin
package com.badmintontracker.analysis.camera

import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.geometry.apply
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test

class RansacTest {
    private val truth = listOf(listOf(1.01, 0.02, 12.0), listOf(-0.015, 0.99, -7.0), listOf(1e-5, 2e-5, 1.0))

    private fun pairs(outlierShare: Double): Pair<List<Point>, List<Point>> {
        val r = Random(3)
        val src = List(150) { Point(r.nextDouble() * 480, r.nextDouble() * 270) }
        val dst = src.mapIndexed { i, p ->
            val q = truth.apply(p.x, p.y)!!
            if (i < outlierShare * src.size) Point(q.x + 20 + r.nextDouble() * 60, q.y - 30 + r.nextDouble() * 60)
            else Point(q.x + (r.nextDouble() - 0.5) * 0.4, q.y + (r.nextDouble() - 0.5) * 0.4)
        }
        return src to dst
    }

    @Test fun thirty_percent_outliers_do_not_move_the_fit() {
        val (src, dst) = pairs(0.3)
        val fit = ransacHomography(src, dst)!!
        (fit.inliers >= 100) shouldBe true
        for (p in listOf(Point(0.0, 0.0), Point(480.0, 0.0), Point(480.0, 270.0), Point(0.0, 270.0))) {
            val a = truth.apply(p.x, p.y)!!
            val b = fit.homography.apply(p.x, p.y)!!
            hypot(a.x - b.x, a.y - b.y) shouldBeLessThan 0.5
        }
    }

    @Test fun the_same_input_gives_the_same_answer() {
        val (src, dst) = pairs(0.3)
        ransacHomography(src, dst)!!.homography shouldBe ransacHomography(src, dst)!!.homography
    }

    @Test fun fewer_than_four_pairs_is_no_fit() {
        ransacHomography(listOf(Point(0.0, 0.0)), listOf(Point(1.0, 1.0))).shouldBeNull()
    }

    @Test fun the_fit_is_normalised() {
        val (src, dst) = pairs(0.0)
        ransacHomography(src, dst)!!.homography[2][2] shouldBe 1.0
    }
}
```

- [ ] **Step 2: Run to see them fail**

Run: `./gradlew :analysis:jvmTest --tests '*LucasKanadeTest*' --tests '*RansacTest*'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

`LucasKanade.kt`:

```kotlin
package com.badmintontracker.analysis.camera

import kotlin.math.sqrt

internal const val PYRAMID_LEVELS = 3
internal const val LK_WINDOW_HALF = 10
internal const val LK_MAX_ITERATIONS = 20
internal const val LK_EPSILON = 0.03
internal const val LK_MIN_EIGEN = 1e-3

private const val WINDOW = (2 * LK_WINDOW_HALF + 1) * (2 * LK_WINDOW_HALF + 1)

/**
 * Pyramidal Lucas-Kanade from one fixed reference image, with everything that
 * depends only on the reference computed once. Design §4.3 step 3.
 *
 * The reference is the court-marking frame and never changes during a run,
 * so each point's template (grey levels and Scharr gradients over its 21x21
 * window, at every level) and its structure tensor are built here, and
 * tracking a frame samples only the target. That is Bouguet's formulation
 * with the reference's gradients, which is what OpenCV's
 * `calcOpticalFlowPyrLK` does.
 */
internal class AnchoredTemplates(reference: List<GreyImage>, val points: List<Pair<Double, Double>>) {
    private val levels = reference.size
    // [level][point] -> values, gx, gy over the window, row-major.
    private val values = Array(levels) { Array(points.size) { DoubleArray(WINDOW) } }
    private val gxs = Array(levels) { Array(points.size) { DoubleArray(WINDOW) } }
    private val gys = Array(levels) { Array(points.size) { DoubleArray(WINDOW) } }
    // [level] -> per point a, b, c of [[a, b], [b, c]], and whether usable.
    private val tensor = Array(levels) { DoubleArray(points.size * 3) }
    private val usable = Array(levels) { BooleanArray(points.size) }

    init {
        for (level in 0 until levels) {
            val img = reference[level]
            val g = scharr(img)
            val gxImg = GreyImage(img.width, img.height, g.gx)
            val gyImg = GreyImage(img.width, img.height, g.gy)
            val scale = 1.0 / (1 shl level)
            points.forEachIndexed { i, (px, py) ->
                val cx = px * scale
                val cy = py * scale
                var a = 0.0
                var b = 0.0
                var c = 0.0
                var k = 0
                for (dy in -LK_WINDOW_HALF..LK_WINDOW_HALF) for (dx in -LK_WINDOW_HALF..LK_WINDOW_HALF) {
                    val ix = gxImg.sample(cx + dx, cy + dy)
                    val iy = gyImg.sample(cx + dx, cy + dy)
                    values[level][i][k] = img.sample(cx + dx, cy + dy)
                    gxs[level][i][k] = ix
                    gys[level][i][k] = iy
                    a += ix * ix; b += ix * iy; c += iy * iy
                    k++
                }
                tensor[level][3 * i] = a
                tensor[level][3 * i + 1] = b
                tensor[level][3 * i + 2] = c
                val minEig = ((a + c) - sqrt((a - c) * (a - c) + 4 * b * b)) / 2.0 / WINDOW
                usable[level][i] = minEig >= LK_MIN_EIGEN
            }
        }
    }

    /**
     * Tracks every point into [target] (a pyramid of the same depth), starting
     * from [guesses] (`x0, y0, x1, y1, ...` at level 0). Returns the positions
     * found and whether each point succeeded.
     */
    fun track(target: List<GreyImage>, guesses: DoubleArray): Pair<DoubleArray, BooleanArray> {
        require(target.size == levels)
        val out = DoubleArray(points.size * 2)
        val ok = BooleanArray(points.size)
        val top = levels - 1
        for (i in points.indices) {
            val (px, py) = points[i]
            // The guess as a displacement, carried down the pyramid (Bouguet).
            var gx = (guesses[2 * i] - px) / (1 shl top)
            var gy = (guesses[2 * i + 1] - py) / (1 shl top)
            var failed = false
            var dx = 0.0
            var dy = 0.0
            for (level in top downTo 0) {
                if (!usable[level][i]) { failed = true; break }
                val img = target[level]
                val scale = 1.0 / (1 shl level)
                val cx = px * scale
                val cy = py * scale
                val a = tensor[level][3 * i]
                val b = tensor[level][3 * i + 1]
                val c = tensor[level][3 * i + 2]
                val det = a * c - b * b
                if (det <= 1e-12) { failed = true; break }
                var vx = 0.0
                var vy = 0.0
                val t = values[level][i]
                val ix = gxs[level][i]
                val iy = gys[level][i]
                for (iteration in 0 until LK_MAX_ITERATIONS) {
                    var bx = 0.0
                    var by = 0.0
                    var k = 0
                    val ox = cx + gx + vx
                    val oy = cy + gy + vy
                    for (wy in -LK_WINDOW_HALF..LK_WINDOW_HALF) for (wx in -LK_WINDOW_HALF..LK_WINDOW_HALF) {
                        val diff = t[k] - img.sample(ox + wx, oy + wy)
                        bx += diff * ix[k]
                        by += diff * iy[k]
                        k++
                    }
                    val ex = (c * bx - b * by) / det
                    val ey = (a * by - b * bx) / det
                    vx += ex
                    vy += ey
                    if (ex * ex + ey * ey <= LK_EPSILON * LK_EPSILON) break
                }
                if (level > 0) {
                    gx = 2 * (gx + vx)
                    gy = 2 * (gy + vy)
                } else {
                    dx = gx + vx
                    dy = gy + vy
                }
            }
            val x = px + dx
            val y = py + dy
            val base = target[0]
            if (failed || x.isNaN() || y.isNaN() || x < 0 || y < 0 || x > base.width - 1 || y > base.height - 1) {
                ok[i] = false
            } else {
                out[2 * i] = x
                out[2 * i + 1] = y
                ok[i] = true
            }
        }
        return out to ok
    }
}
```

`Ransac.kt`:

```kotlin
package com.badmintontracker.analysis.camera

import com.badmintontracker.analysis.geometry.Matrix3x3
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.geometry.apply
import com.badmintontracker.analysis.geometry.calculateHomography
import kotlin.random.Random

internal class RansacResult(val homography: Matrix3x3, val inliers: Int)

/**
 * RANSAC over 4-point DLT fits, then a least-squares refit on the inliers, as
 * OpenCV's `findHomography(src, dst, RANSAC, threshold, maxIters)` does.
 * Design §4.3 step 4. The generator is seeded so a run is repeatable on every
 * platform. The result is normalised so `h22 == 1.0`.
 */
internal fun ransacHomography(
    src: List<Point>,
    dst: List<Point>,
    threshold: Double = 1.0,
    iterations: Int = 300,
    random: Random = Random(0x5EED),
): RansacResult? {
    val n = minOf(src.size, dst.size)
    if (n < 4) return null
    val t2 = threshold * threshold

    fun inliersOf(h: Matrix3x3): BooleanArray = BooleanArray(n) { i ->
        val p = h.apply(src[i].x, src[i].y)
        p != null && (p.x - dst[i].x) * (p.x - dst[i].x) + (p.y - dst[i].y) * (p.y - dst[i].y) <= t2
    }

    var best: Matrix3x3? = null
    var bestCount = -1
    val pick = IntArray(4)
    repeat(iterations) {
        var filled = 0
        while (filled < 4) {
            val c = random.nextInt(n)
            if ((0 until filled).none { pick[it] == c }) pick[filled++] = c
        }
        val h = calculateHomography(pick.map { src[it] }, pick.map { dst[it] }) ?: return@repeat
        val count = inliersOf(h).count { it }
        if (count > bestCount) {
            bestCount = count
            best = h
        }
    }
    val seed = best ?: return null
    val mask = inliersOf(seed)
    val refit = calculateHomography(
        src.filterIndexed { i, _ -> i < n && mask[i] },
        dst.filterIndexed { i, _ -> i < n && mask[i] },
    ) ?: seed
    val final = normalise(refit) ?: return null
    return RansacResult(final, inliersOf(final).count { it })
}

/** Divided through so the bottom-right entry is exactly 1.0; null when it is ~0. */
internal fun normalise(h: Matrix3x3): Matrix3x3? {
    val s = h[2][2]
    if (kotlin.math.abs(s) < 1e-12) return null
    return List(3) { r -> List(3) { c -> if (r == 2 && c == 2) 1.0 else h[r][c] / s } }
}
```

- [ ] **Step 4: Run to see them pass**

Run: `./gradlew :analysis:jvmTest --tests '*LucasKanadeTest*' --tests '*RansacTest*'`
Expected: PASS. If `good` counts fall short because `LK_MIN_EIGEN` rejects points, print the minimum normalised eigenvalue over the synthetic corners and set the constant an order of magnitude below it; state the measured value in its KDoc.

- [ ] **Step 5: Commit**

```bash
git add analysis/src/commonMain/kotlin/com/badmintontracker/analysis/camera analysis/src/commonTest/kotlin/com/badmintontracker/analysis/camera
git commit -m "feat(analysis): anchored Lucas-Kanade and a repeatable RANSAC homography"
```

---

### Task 4: CameraMotion and the tracker

**Files:**
- Create: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/camera/CameraMotion.kt`, `.../camera/CameraMotionTracker.kt`
- Test: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/camera/CameraMotionTest.kt`, `.../camera/CameraMotionTrackerTest.kt`

**Interfaces:**
- Consumes: Tasks 1-3.
- Produces:
  - `data class CameraSample(val timestamp: Double, val referenceToFrame: Matrix3x3)`
  - `data class CameraMotion(val referenceTimestamp: Double, val samples: List<CameraSample>) { fun at(timestamp: Double): Matrix3x3? }` (samples sorted by timestamp, first kept on duplicates, enforced in `init` by construction through `CameraMotion.of(referenceTimestamp, samples)`; the primary constructor requires sorted and distinct timestamps).
  - `class CameraMotionTracker(markingTimeS: Double, sourceWidth: Int, sourceHeight: Int) { val trackerWidth: Int; val trackerHeight: Int; fun wants(timestamp: Double): Boolean; fun offer(timestamp: Double, image: GreyImage); fun finish(): CameraMotion?; val acceptedSamples: Int; val droppedSamples: Int }`
  - Constants: `CameraMotion.EXACT_S = 1e-6`, `CameraMotion.MAX_BLEND_GAP_S = 0.5`, `CameraMotion.MAX_HOLD_S = 0.15`, `CameraMotionTracker.SAMPLE_INTERVAL_S = 0.1`, `CameraMotionTracker.MIN_INLIERS = 40`, `CameraMotionTracker.MIN_CORNERS = 40`, `CameraMotionTracker.MAX_CORNER_SHIFT = 0.25`.

- [ ] **Step 1: Write the failing tests**

`CameraMotionTest.kt`:

```kotlin
package com.badmintontracker.analysis.camera

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class CameraMotionTest {
    private fun shift(x: Double) = listOf(listOf(1.0, 0.0, x), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0))
    private val motion = CameraMotion.of(
        0.1,
        listOf(CameraSample(0.1, shift(0.0)), CameraSample(0.2, shift(10.0)), CameraSample(1.2, shift(50.0))),
    )

    @Test fun an_exact_sample_is_returned_as_is() { motion.at(0.2)!![0][2] shouldBe 10.0 }
    @Test fun between_close_samples_is_a_blend() { motion.at(0.15)!![0][2] shouldBe (5.0 plusOrMinus 1e-9) }
    @Test fun a_blend_is_normalised() { motion.at(0.15)!![2][2] shouldBe 1.0 }
    @Test fun inside_a_long_gap_near_a_sample_holds_it() { motion.at(0.3)!![0][2] shouldBe 10.0 }
    @Test fun deep_inside_a_long_gap_is_unknown() { motion.at(0.7).shouldBeNull() }
    @Test fun just_before_the_first_holds_it() { motion.at(0.0)!![0][2] shouldBe 0.0 }
    @Test fun long_after_the_last_is_unknown() { motion.at(1.5).shouldBeNull() }

    @Test fun duplicate_timestamps_keep_the_first() {
        val m = CameraMotion.of(0.0, listOf(CameraSample(0.0, shift(1.0)), CameraSample(0.0, shift(2.0))))
        m.samples.size shouldBe 1
        m.at(0.0)!![0][2] shouldBe 1.0
    }

    @Test fun two_equal_motions_are_equal() {
        CameraMotion.of(0.1, listOf(CameraSample(0.1, shift(3.0)))) shouldBe
            CameraMotion.of(0.1, listOf(CameraSample(0.1, shift(3.0))))
    }
}
```

`CameraMotionTrackerTest.kt`:

```kotlin
package com.badmintontracker.analysis.camera

import com.badmintontracker.analysis.geometry.apply
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.hypot
import kotlin.test.Test

class CameraMotionTrackerTest {
    private val base = Synthetic.texture(480, 270, 11)

    /** A slow pan: x shifts 0.8 px per 1/30 s, y drifts, like a hand. */
    private fun panAt(t: Double) =
        listOf(listOf(1.0, 0.0, 24.0 * t), listOf(0.0, 1.0, -9.0 * t), listOf(0.0, 0.0, 1.0))

    private fun run(tracker: CameraMotionTracker, times: List<Double>, frameAt: (Double) -> GreyImage) {
        for (t in times) if (tracker.wants(t)) tracker.offer(t, frameAt(t))
    }

    private fun cornerError(expected: List<List<Double>>, got: List<List<Double>>): Double =
        listOf(0.0 to 0.0, 1920.0 to 0.0, 1920.0 to 1080.0, 0.0 to 1080.0).maxOf { (x, y) ->
            val a = expected.apply(x, y)!!
            val b = got.apply(x, y)!!
            hypot(a.x - b.x, a.y - b.y)
        }

    /** The same pan in source pixels: tracker-space pan scaled by S. */
    private fun sourcePan(t: Double): List<List<Double>> {
        val p = panAt(t)
        return listOf(listOf(1.0, 0.0, p[0][2] * 4), listOf(0.0, 1.0, p[1][2] * 4), listOf(0.0, 0.0, 1.0))
    }

    @Test fun a_pan_is_followed_at_ten_hertz_to_a_pixel_in_source_space() {
        val tracker = CameraMotionTracker(0.1, 1920, 1080)
        val times = (0 until 90).map { it / 30.0 }
        // Frames are the texture panned relative to the marking frame at t = 0.1.
        run(tracker, times) { t -> Synthetic.warp(base, multiplyPan(t)) }
        val motion = tracker.finish().shouldNotBeNull()
        motion.samples.size shouldBe 30
        for (t in listOf(0.0, 0.5, 1.0, 2.9)) {
            cornerError(sourcePanRelative(t), motion.at(t)!!) shouldBeLessThan 2.0
        }
    }

    @Test fun the_marking_frame_is_the_identity_and_is_sampled_even_off_a_boundary() {
        val tracker = CameraMotionTracker(0.1234, 1920, 1080)
        val times = (0 until 12).map { it / 30.0 }
        run(tracker, times) { base }
        val motion = tracker.finish().shouldNotBeNull()
        motion.referenceTimestamp shouldBe (4 / 30.0)
        motion.at(4 / 30.0) shouldBe listOf(listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0))
    }

    @Test fun frames_before_a_late_marking_time_are_kept() {
        val tracker = CameraMotionTracker(1.0, 1920, 1080)
        val times = (0 until 60).map { it / 30.0 }
        run(tracker, times) { t -> Synthetic.warp(base, multiplyPanFrom(t, 1.0)) }
        val motion = tracker.finish().shouldNotBeNull()
        motion.samples.first().timestamp shouldBe 0.0
        cornerError(sourcePanRelativeFrom(0.0, 1.0), motion.at(0.0)!!) shouldBeLessThan 2.0
    }

    @Test fun variable_frame_rate_samples_by_time_not_by_count() {
        val tracker = CameraMotionTracker(0.1, 1920, 1080)
        // Irregular spacing, with a duplicate timestamp as the cloud produces.
        val times = listOf(0.0, 0.0, 0.03, 0.07, 0.1, 0.13, 0.19, 0.21, 0.25, 0.33, 0.41, 0.52)
        run(tracker, times) { base }
        tracker.finish().shouldNotBeNull().samples.map { it.timestamp } shouldBe
            listOf(0.0, 0.1, 0.21, 0.33, 0.41, 0.52)
    }

    @Test fun a_textureless_marking_frame_gives_up() {
        val tracker = CameraMotionTracker(0.1, 1920, 1080)
        run(tracker, listOf(0.0, 0.1, 0.2)) { GreyImage(480, 270, FloatArray(480 * 270) { 40f }) }
        tracker.finish().shouldBeNull()
        tracker.wants(0.3) shouldBe false
    }

    @Test fun a_video_that_ends_before_the_marking_time_has_no_camera() {
        val tracker = CameraMotionTracker(1.0, 1920, 1080)
        run(tracker, listOf(0.0, 0.1, 0.2)) { base }
        tracker.finish().shouldBeNull()
    }

    @Test fun a_frame_from_another_scene_is_dropped_not_accepted() {
        val tracker = CameraMotionTracker(0.1, 1920, 1080)
        val other = Synthetic.texture(480, 270, 99)
        run(tracker, listOf(0.1, 0.2, 0.3)) { t -> if (t == 0.2) other else base }
        val motion = tracker.finish().shouldNotBeNull()
        motion.samples.map { it.timestamp } shouldBe listOf(0.1, 0.3)
        tracker.droppedSamples shouldBe 1
    }

    @Test fun a_portrait_source_uses_a_portrait_tracker() {
        val tracker = CameraMotionTracker(0.1, 1080, 1920)
        tracker.trackerWidth shouldBe 270
        tracker.trackerHeight shouldBe 480
    }

    // The marking frame is at t = 0.1, so a frame at t shows the pan from 0.1 to t.
    private fun multiplyPan(t: Double) = panRelative(t, 0.1)
    private fun multiplyPanFrom(t: Double, ref: Double) = panRelative(t, ref)
    private fun panRelative(t: Double, ref: Double) =
        listOf(listOf(1.0, 0.0, 24.0 * (t - ref)), listOf(0.0, 1.0, -9.0 * (t - ref)), listOf(0.0, 0.0, 1.0))
    private fun sourcePanRelative(t: Double) = sourcePanRelativeFrom(t, 0.1)
    private fun sourcePanRelativeFrom(t: Double, ref: Double) =
        listOf(listOf(1.0, 0.0, 96.0 * (t - ref)), listOf(0.0, 1.0, -36.0 * (t - ref)), listOf(0.0, 0.0, 1.0))
}
```

Remove the unused `panAt` and `sourcePan` helpers if the compiler warns; they are there to show the arithmetic (tracker shift × 4 = source shift for 1920 → 480).

- [ ] **Step 2: Run to see them fail**

Run: `./gradlew :analysis:jvmTest --tests '*CameraMotion*'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

`CameraMotion.kt`:

```kotlin
package com.badmintontracker.analysis.camera

import com.badmintontracker.analysis.geometry.Matrix3x3
import kotlin.math.abs

/**
 * Where the camera was at one moment: [referenceToFrame] maps court-marking
 * frame pixels to this frame's pixels, both in decoded source pixels, with
 * `h22 == 1.0`.
 */
data class CameraSample(val timestamp: Double, val referenceToFrame: Matrix3x3)

/**
 * The camera over a whole video, as the tracker sampled it. Design §4.4.
 *
 * Build through [of], which sorts and drops duplicate timestamps.
 */
data class CameraMotion(val referenceTimestamp: Double, val samples: List<CameraSample>) {
    init {
        for (i in 1 until samples.size) {
            require(samples[i].timestamp > samples[i - 1].timestamp) { "samples must be sorted and distinct" }
        }
    }

    /**
     * The camera at [timestamp], or null when it is not known there. In order:
     * an exact sample; a blend of two neighbours no more than
     * [MAX_BLEND_GAP_S] apart; the nearest sample within [MAX_HOLD_S]; null.
     */
    fun at(timestamp: Double): Matrix3x3? {
        if (samples.isEmpty()) return null
        var lo = 0
        var hi = samples.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (samples[mid].timestamp < timestamp) lo = mid + 1 else hi = mid
        }
        val after = samples[lo].takeIf { it.timestamp >= timestamp }
        val before = if (after == null) samples[lo] else samples.getOrNull(lo - 1)
        if (after != null && abs(after.timestamp - timestamp) <= EXACT_S) return after.referenceToFrame
        if (before != null && abs(before.timestamp - timestamp) <= EXACT_S) return before.referenceToFrame
        if (before != null && after != null && after.timestamp - before.timestamp <= MAX_BLEND_GAP_S) {
            val w = (timestamp - before.timestamp) / (after.timestamp - before.timestamp)
            return blend(before.referenceToFrame, after.referenceToFrame, w)
        }
        val nearest = listOfNotNull(before, after).minByOrNull { abs(it.timestamp - timestamp) } ?: return null
        return if (abs(nearest.timestamp - timestamp) <= MAX_HOLD_S) nearest.referenceToFrame else null
    }

    private fun blend(a: Matrix3x3, b: Matrix3x3, w: Double): Matrix3x3 {
        val m = List(3) { r -> List(3) { c -> a[r][c] * (1 - w) + b[r][c] * w } }
        val s = m[2][2]
        return List(3) { r -> List(3) { c -> if (r == 2 && c == 2) 1.0 else m[r][c] / s } }
    }

    companion object {
        const val EXACT_S = 1e-6
        const val MAX_BLEND_GAP_S = 0.5
        const val MAX_HOLD_S = 0.15

        /** Sorted by timestamp; where two share one (within [EXACT_S]), the first is kept. */
        fun of(referenceTimestamp: Double, samples: List<CameraSample>): CameraMotion {
            val sorted = samples.withIndex().sortedWith(compareBy({ it.value.timestamp }, { it.index })).map { it.value }
            val kept = ArrayList<CameraSample>()
            for (s in sorted) if (kept.isEmpty() || s.timestamp - kept.last().timestamp > EXACT_S) kept.add(s)
            return CameraMotion(referenceTimestamp, kept)
        }
    }
}
```

`CameraMotionTracker.kt`:

```kotlin
package com.badmintontracker.analysis.camera

import com.badmintontracker.analysis.geometry.Matrix3x3
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.geometry.apply
import com.badmintontracker.analysis.geometry.invert3x3
import com.badmintontracker.analysis.geometry.multiply3x3
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Follows the camera through one decode pass. Design §4.
 *
 * Construct with the marking time `T` and the source size; call [wants] for
 * every decoded frame and [offer] with the downsampled luma when it says yes;
 * call [finish] after the last frame. Single-threaded: the decode thread owns
 * it.
 */
class CameraMotionTracker(
    private val markingTimeS: Double,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
) {
    val trackerWidth: Int
    val trackerHeight: Int
    private val toSource: Matrix3x3
    private val fromSource: Matrix3x3

    init {
        val (w, h) = Luma.trackerSize(sourceWidth, sourceHeight)
        trackerWidth = w
        trackerHeight = h
        toSource = Luma.trackerToSource(sourceWidth, sourceHeight, w, h)
        fromSource = invert3x3(toSource)!!
    }

    var acceptedSamples: Int = 0
        private set
    var droppedSamples: Int = 0
        private set

    private var lastBucket = Long.MIN_VALUE
    private var templates: AnchoredTemplates? = null
    private var referenceTimestamp = 0.0
    private var gaveUp = false
    private val pending = ArrayList<Pair<Double, GreyImage>>()
    private val samples = ArrayList<CameraSample>()
    private var guess: Matrix3x3 = IDENTITY

    private fun bucket(t: Double): Long = floor(t / SAMPLE_INTERVAL_S + 1e-9).toLong()

    /**
     * Whether the frame at [timestamp] should be offered: the first frame of
     * each 0.1 s bucket, and the marking frame (the first at or after `T`)
     * whether or not it starts a bucket.
     */
    fun wants(timestamp: Double): Boolean {
        if (gaveUp) return false
        if (templates == null && timestamp >= markingTimeS - CameraMotion.EXACT_S) return true
        return bucket(timestamp) > lastBucket
    }

    /** One sampled frame's luma at tracker size. */
    fun offer(timestamp: Double, image: GreyImage) {
        require(image.width == trackerWidth && image.height == trackerHeight) {
            "expected $trackerWidth x $trackerHeight, got ${image.width} x ${image.height}"
        }
        if (bucket(timestamp) > lastBucket) lastBucket = bucket(timestamp)
        if (gaveUp) return
        if (templates == null) {
            if (timestamp < markingTimeS - CameraMotion.EXACT_S) {
                pending.add(timestamp to image)
                return
            }
            val reference = pyramid(image, PYRAMID_LEVELS)
            val corners = shiTomasi(image)
            if (corners.size < MIN_CORNERS) {
                gaveUp = true
                pending.clear()
                return
            }
            templates = AnchoredTemplates(reference, corners)
            referenceTimestamp = timestamp
            samples.add(CameraSample(timestamp, IDENTITY))
            acceptedSamples++
            // Earlier frames, nearest the marking frame first, each guessing
            // from the one after it; then the forward pass resumes from identity.
            guess = IDENTITY
            for ((t, img) in pending.sortedByDescending { it.first }) track(t, img)
            pending.clear()
            guess = IDENTITY
            return
        }
        track(timestamp, image)
    }

    private fun track(timestamp: Double, image: GreyImage) {
        val t = templates ?: return
        val target = pyramid(image, PYRAMID_LEVELS)
        val guesses = DoubleArray(t.points.size * 2)
        t.points.forEachIndexed { i, (x, y) ->
            val g = guess.apply(x, y)
            guesses[2 * i] = g?.x ?: x
            guesses[2 * i + 1] = g?.y ?: y
        }
        val (found, ok) = t.track(target, guesses)
        val src = ArrayList<Point>()
        val dst = ArrayList<Point>()
        t.points.forEachIndexed { i, (x, y) ->
            if (ok[i]) {
                src.add(Point(x, y))
                dst.add(Point(found[2 * i], found[2 * i + 1]))
            }
        }
        val fit = if (src.size >= MIN_INLIERS) ransacHomography(src, dst) else null
        if (fit == null || fit.inliers < MIN_INLIERS || !plausible(fit.homography)) {
            droppedSamples++
            return
        }
        guess = fit.homography
        val inSource = normalise(multiply3x3(toSource, multiply3x3(fit.homography, fromSource)))!!
        samples.add(CameraSample(timestamp, inSource))
        acceptedSamples++
    }

    /** Design §4.3 step 5: every image corner moves at most a quarter of the width, and no flip or collapse. */
    private fun plausible(h: Matrix3x3): Boolean {
        val limit = MAX_CORNER_SHIFT * trackerWidth
        for ((x, y) in listOf(0.0 to 0.0, trackerWidth.toDouble() to 0.0,
            trackerWidth.toDouble() to trackerHeight.toDouble(), 0.0 to trackerHeight.toDouble())) {
            val p = h.apply(x, y) ?: return false
            if (hypot(p.x - x, p.y - y) > limit) return false
        }
        val det = h[0][0] * h[1][1] - h[0][1] * h[1][0]
        return det in 0.5..2.0
    }

    /** The motion so far, or null when no marking frame was ever tracked. */
    fun finish(): CameraMotion? {
        if (templates == null) return null
        return CameraMotion.of(referenceTimestamp, samples)
    }

    companion object {
        const val SAMPLE_INTERVAL_S = 0.1
        const val MIN_INLIERS = 40
        const val MIN_CORNERS = 40
        const val MAX_CORNER_SHIFT = 0.25
        private val IDENTITY: Matrix3x3 = listOf(listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0))
    }
}
```

Keep `normalise` (Task 3) as the single normaliser. With a frame sampled at the start of a bucket and the marking frame both in the same bucket (`T = 0.1234` in the test), both are offered, which is intended. Once the marking frame has been offered, `templates` is set (or `gaveUp` is), so the marking clause of `wants` never fires twice.

- [ ] **Step 4: Run to see them pass**

Run: `./gradlew :analysis:jvmTest --tests '*CameraMotion*'` then `./gradlew :analysis:iosSimulatorArm64Test --tests '*CameraMotion*'`
Expected: PASS on both. Record the JVM wall time of `a_pan_is_followed_at_ten_hertz_to_a_pixel_in_source_space` divided by its 30 samples in the commit message as ms per sample on the host.

- [ ] **Step 5: Commit**

```bash
git add analysis/src/commonMain/kotlin/com/badmintontracker/analysis/camera analysis/src/commonTest/kotlin/com/badmintontracker/analysis/camera
git commit -m "feat(analysis): follow the camera from the marking frame at ten hertz"
```

---

### Task 5: The real video, measured against the paint

**Files:**
- Create: `tools/models/camera_motion_probe.py`
- Create: `analysis/src/jvmTest/kotlin/com/badmintontracker/analysis/camera/CameraMotionCorpusTest.kt`
- Modify: `tools/models/README.md` (a short section on the probe)

**Interfaces:**
- Consumes: `CameraMotionTracker`, `Luma` (Tasks 1, 4).
- Produces: `~/shuttl-corpus/IMG_5892/y/<index>.y` raw Y planes (1920x1080 bytes, video range, as decoded) for every 10 Hz sample and the marking frame; `~/shuttl-corpus/IMG_5892/samples.json` (`[{index, timestamp}]`); `~/shuttl-corpus/IMG_5892/paint.json` (court-metre points on the near short service line, near baseline and near long service line); `~/shuttl-corpus/IMG_5892/opencv_motion.json` (OpenCV anchored tracker at §4 settings, source-pixel homographies per sample timestamp); `~/shuttl-corpus/IMG_5892/frame_times.json` (every decoded frame's timestamp, by 0-based index, from ffprobe). Existing files there: `marks_ideal.json`, `H_ideal.json`, `reference_tracks.json`, `nano_pose.pkl`, `source.mov`.

- [ ] **Step 1: Write the probe**

`tools/models/camera_motion_probe.py` must:

1. Decode `~/shuttl-corpus/IMG_5892/source.mov` sequentially with ffmpeg piping raw Y planes: `ffmpeg -i source.mov -vf extractplanes=y -f rawvideo -pix_fmt gray -` (one 1920x1080 byte frame per decoded frame, no range conversion), and read timestamps with `ffprobe -select_streams v -show_entries frame=pts_time -of csv=p=0`.
2. Choose the sampled frames with exactly the tracker's rule (`floor(t / 0.1 + 1e-9)` bucket crossings, plus the first frame with `t >= 0.1 - 1e-6`), and write each chosen frame's plane to `y/<index>.y` and the list to `samples.json`.
3. Run OpenCV's anchored tracker on `cv2.resize(plane, (480, 270), interpolation=cv2.INTER_AREA)` with the §4 settings (`goodFeaturesToTrack(ref, 150, 0.01, 8, blockSize=3)` minus a 10 px border, `calcOpticalFlowPyrLK(..., winSize=(21,21), maxLevel=2, criteria=(COUNT|EPS, 20, 0.03), flags=OPTFLOW_USE_INITIAL_FLOW)`, `findHomography(..., RANSAC, 1.0, maxIters=300)`, the same acceptance rule), scale each result to source pixels with the §4.1 `S`, and write `opencv_motion.json`.
4. Write `paint.json`: for x in `linspace(0.8, 5.3, 8)`, the court points `(x, 13.4/2 + 1.98)`, `(x, 13.4)`, `(x, 13.4 - 0.76)`.
5. Print, for OpenCV's result, the painted-line error (the check in step 2 of Task 5's test below) so the Kotlin test's thresholds are grounded.

Base the tracker code on `/tmp/hm/cost/spec_cfg.py` if it still exists; otherwise from the settings above. Run it:

Run: `python3 tools/models/camera_motion_probe.py`
Expected: about 728 sample files, and a printed OpenCV painted-line error with median under 1 px and p95 around 2 px.

- [ ] **Step 2: Write the corpus-gated test**

`CameraMotionCorpusTest.kt` (jvmTest): skip with a printed reason when `~/shuttl-corpus/IMG_5892/samples.json` is absent, unless `ANALYSIS_REQUIRE_CORPUS=1`, in which case fail (the same convention as the other corpus tests, see `analysis/src/jvmSharedTest/.../CorpusFixture.jvmShared.kt`). It must:

1. Feed every sample in `samples.json` order through `CameraMotionTracker(0.1, 1920, 1080)`: read `y/<index>.y`, `Luma.downsample(bytes, 0, 1920, 1080, 1920, 480, 270)`, `offer(timestamp, image)`. Assert `wants(timestamp)` is true for each (the probe used the same rule).
2. For each sample, project the `paint.json` points into the frame with `invert3x3(H_ideal)` then the sample's `M`, and at each projected point find the painted line's centre within ±12 px vertically in the plane (grey-weighted centroid of values above the column's local median + 40, the method used in `/tmp/hm/cost/acc.py` `line_err`). Assert the vertical distance has median ≤ 1.0 px and p95 ≤ 2.0 px.
3. Compare with `opencv_motion.json` at every common timestamp: map the four source-frame corners by both; assert the median of the per-sample worst corner distance is ≤ 1.0 px and the p95 ≤ 3.0 px.
4. Assert `droppedSamples == 0` and print the mean milliseconds per `offer` (the host cost).

- [ ] **Step 3: Run it**

Run: `ANALYSIS_REQUIRE_CORPUS=1 ./gradlew :analysis:jvmTest --tests '*CameraMotionCorpusTest*' -i`
Expected: PASS, with the printed ms per sample. If the painted-line bound fails, compare against OpenCV's own number from the probe before touching the tracker: the test's bound is only meaningful relative to what OpenCV achieves on the same planes.

- [ ] **Step 4: Commit**

```bash
git add tools/models/camera_motion_probe.py tools/models/README.md analysis/src/jvmTest/kotlin/com/badmintontracker/analysis/camera/CameraMotionCorpusTest.kt
git commit -m "test(analysis): the camera tracker on a real handheld recording, against the paint"
```

---

### Task 6: RawInference carries the camera (version 2)

**Files:**
- Modify: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/raw/RawInference.kt`, `.../raw/RawInferenceCodec.kt`
- Modify: `iosApp/Sources/LocalAnalysis/IosLocalInferenceEngine.swift:191-203` (pass `camera: nil` for now)
- Test: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/raw/RawInferenceCodecTest.kt`

**Interfaces:**
- Consumes: `CameraMotion`, `CameraSample` (Task 4).
- Produces: `data class RawInference(val header: RawHeader, val frames: List<RawFrame>, val camera: CameraMotion? = null)`; `RawInferenceCodec.VERSION = 2`; `RawInferenceCodec.READABLE_VERSIONS = setOf(1, 2)`.

- [ ] **Step 1: Write the failing tests**

In `RawInferenceCodecTest.kt`:
- Replace `an_unknown_version_is_rejected_by_name` so it pokes version `3` (not 2) and still expects the message to name the version.
- Keep `the_python_writer_and_this_reader_agree` on the existing `cloud-poses.rawi` (version 1) and assert `camera` is null. Task 13 adds the version 2 fixture test.
- Add:

```kotlin
    @Test fun a_camera_round_trips() {
        val m = listOf(listOf(1.001, 0.002, 14.5), listOf(-0.003, 0.999, -8.25), listOf(1e-6, -2e-6, 1.0))
        val raw = sample().copy(camera = CameraMotion.of(0.1, listOf(
            CameraSample(0.1, listOf(listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0))),
            CameraSample(0.2, m),
        )))
        RawInferenceCodec.decode(RawInferenceCodec.encode(raw)) shouldBe raw
    }

    @Test fun no_camera_round_trips_as_no_camera() {
        val raw = sample()
        RawInferenceCodec.decode(RawInferenceCodec.encode(raw)).camera shouldBe null
    }

    @Test fun encode_writes_version_2() {
        val bytes = RawInferenceCodec.encode(sample())
        (bytes[RawInferenceCodec.VERSION_OFFSET].toInt() and 0xFF) shouldBe 2
    }

    @Test fun a_version_1_stream_still_reads_with_no_camera() {
        // Encode, then rewrite it as the version 1 a deployed worker produced:
        // version byte 1 and no camera section (the trailing hasCamera byte removed).
        val v2 = RawInferenceCodec.encode(sample())
        val v1 = v2.copyOf(v2.size - 1).also { it[RawInferenceCodec.VERSION_OFFSET] = 1 }
        RawInferenceCodec.decode(v1).camera shouldBe null
    }

    @Test fun bytes_after_the_camera_section_are_rejected() {
        val bytes = RawInferenceCodec.encode(sample()) + byteArrayOf(0)
        shouldThrow<IllegalArgumentException> { RawInferenceCodec.decode(bytes) }
    }

    @Test fun a_truncated_camera_section_is_rejected() {
        val m = listOf(listOf(1.0, 0.0, 3.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0))
        val bytes = RawInferenceCodec.encode(sample().copy(camera = CameraMotion.of(0.1, listOf(CameraSample(0.1, m)))))
        shouldThrow<IllegalArgumentException> { RawInferenceCodec.decode(bytes.copyOf(bytes.size - 4)) }
    }
```

(`sample()` is the test file's existing builder of a small `RawInference`; if it is named differently, use that name.)

- [ ] **Step 2: Run to see them fail**

Run: `./gradlew :analysis:jvmTest --tests '*RawInferenceCodecTest*'`
Expected: compilation failure on `camera`.

- [ ] **Step 3: Implement**

- `RawInference.kt`: add `val camera: CameraMotion? = null` as the last constructor parameter, with KDoc: "Where the camera was, frame by frame, when the pass followed it (design `2026-09-27-camera-motion-design.md` §5.1). Null when it did not: a version 1 file, a rallies-only pass, or a marking frame with nothing to follow. Selection then uses the fixed marks, as before."
- `RawInferenceCodec.kt`: `VERSION = 2`, `val READABLE_VERSIONS = setOf(1, VERSION)`. Update the layout KDoc with the camera section from the Global Constraints. `encode`: after the frames write `byte(if (camera == null) 0 else 1)`; if present `double(referenceTimestamp)`, `int(samples.size)`, then per sample `double(timestamp)` and the eight entries `h00 h01 h02 h10 h11 h12 h20 h21` as doubles. `decode`: accept `version in READABLE_VERSIONS` (message unchanged for others); after the frames, when version is 2 read the flag and the section (count through `r.count(CAMERA_SAMPLE_BYTES)` with `CAMERA_SAMPLE_BYTES = 8 + 8 * 8`), build `CameraMotion(referenceTimestamp, samples)` with `h22 = 1.0` (use the primary constructor: a written file is already sorted and distinct; if it is not, `require` throws, which is the right answer for a corrupt file). Then `require(r.remaining() == 0)` with the message "trailing bytes after RawInference stream". Add `fun remaining(): Int = buf.size - pos` to `Reader`. When version is 1 also require nothing remains.
- `IosLocalInferenceEngine.swift`: the `RawInference(header:frames:)` call gains `camera: nil` (SKIE has no default arguments). Task 10 replaces it.

- [ ] **Step 4: Run to see them pass**

Run: `./gradlew :analysis:jvmTest :analysis:iosSimulatorArm64Test` and `./gradlew :shared:jvmTest` (the shared module's cloud-pose and coordinator tests encode through this codec).
Expected: PASS. `LocalPipelineEndToEndTest.kt:84` (androidTest) compares `decode(encode(raw))` with `raw`; it still holds because the camera round-trips.

- [ ] **Step 5: Commit**

```bash
git add analysis/src/commonMain/kotlin/com/badmintontracker/analysis/raw analysis/src/commonTest/kotlin/com/badmintontracker/analysis/raw iosApp/Sources/LocalAnalysis/IosLocalInferenceEngine.swift
git commit -m "feat(analysis): the pose file carries where the camera was (RawInference version 2)"
```

---

### Task 7: Selection follows the camera

**Files:**
- Modify: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/player/NearPlayer.kt:62-75` (`RejectionReason`)
- Modify: `analysis/src/commonMain/kotlin/com/badmintontracker/analysis/player/PlayerTrack.kt:32-43, 97-151`
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/local/LocalAnalysisCoordinator.kt:146-157`, `shared/src/commonMain/kotlin/com/badmintontracker/shared/local/CloudPoseCoordinator.kt:64-66` (logs)
- Modify: Swift `PlayerPose(...)` call sites: `iosApp/Sources/LocalAnalysis/SkeletonStore.swift:305`, `iosApp/Tests/AnalysisStoreTests.swift:128`, `iosApp/Tests/SkeletonPanelTests.swift:188` (add `camera: nil`)
- Test: `analysis/src/commonTest/kotlin/com/badmintontracker/analysis/player/SelectPlayersTest.kt`

**Interfaces:**
- Consumes: `RawInference.camera` (Task 6), `invert3x3`, `apply` (Task 1).
- Produces: `RejectionReason.CAMERA_LOST` (last entry); `data class PlayerPose(frame, timestamp, keypoints, confidence, val camera: Matrix3x3? = null)`.

- [ ] **Step 1: Write the failing tests**

Add to `SelectPlayersTest.kt` (reuse its existing helpers for building a court and a person standing at a court point; the ones below name what they must do):

```kotlin
    @Test fun a_panned_frame_finds_the_player_where_they_stand() {
        // The camera panned 80 px up after marking: the person's pixels moved by the same shift.
        val pan = listOf(listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, -80.0), listOf(0.0, 0.0, 1.0))
        val standing = Point(3.0, 11.0) // court metres, near half
        val person = personAt(standing).movedBy(pan)
        val raw = rawOf(listOf(frameWith(person, timestamp = 0.5)), camera = CameraMotion.of(0.1, listOf(
            CameraSample(0.1, IDENTITY), CameraSample(0.5, pan))))
        val near = selectPlayers(raw, marks).first { it.side == CourtSide.NEAR }
        near.track.samples.single().courtPosition.x shouldBe (3.0 plusOrMinus 0.01)
        near.track.samples.single().courtPosition.y shouldBe (11.0 plusOrMinus 0.01)
        near.poses.single().camera shouldBe pan
        // The pose keeps the frame's own pixels, for the overlay.
        near.poses.single().keypoints shouldBe person.keypoints
    }

    @Test fun without_the_camera_the_same_frame_is_lost_to_the_far_side_or_placed_wrongly() {
        val pan = listOf(listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, -80.0), listOf(0.0, 0.0, 1.0))
        val person = personAt(Point(3.0, 11.0)).movedBy(pan)
        val near = selectPlayers(rawOf(listOf(frameWith(person, timestamp = 0.5)), camera = null), marks)
            .first { it.side == CourtSide.NEAR }
        val s = near.track.samples.singleOrNull()
        (s == null || kotlin.math.abs(s.courtPosition.y - 11.0) > 0.5) shouldBe true
    }

    @Test fun a_frame_whose_camera_is_unknown_is_camera_lost_on_both_sides() {
        val raw = rawOf(
            listOf(frameWith(personAt(Point(3.0, 11.0)), timestamp = 5.0)),
            camera = CameraMotion.of(0.1, listOf(CameraSample(0.1, IDENTITY))),
        )
        selectPlayers(raw, marks).forEach {
            it.track.samples shouldBe emptyList()
            it.track.rejections[RejectionReason.CAMERA_LOST] shouldBe 1
            it.track.framesWithPose shouldBe 1
        }
    }

    @Test fun no_camera_section_is_todays_selection_exactly() {
        val frames = listOf(frameWith(personAt(Point(3.0, 11.0)), timestamp = 0.5))
        selectPlayers(rawOf(frames, camera = null), marks).first().poses.single().camera shouldBe null
    }

    @Test fun camera_lost_comes_after_every_existing_reason() {
        RejectionReason.entries.last() shouldBe RejectionReason.CAMERA_LOST
        RejectionReason.BAD_COURT.ordinal shouldBe 5
    }
```

Write the helpers `personAt(court: Point): PosePerson`-equivalent in the raw form the test file already uses (`RawPerson` with confident ankles at the pixel `invert3x3(marks.homography()!!)` maps the court point to, shoulders and hips a plausible torso above), `RawPerson.movedBy(m)` (every keypoint mapped by `m`), `frameWith(person, timestamp)` and `rawOf(frames, camera)`. If the file already has equivalents, extend those.

- [ ] **Step 2: Run to see them fail**

Run: `./gradlew :analysis:jvmTest --tests '*SelectPlayersTest*'`
Expected: compilation failure (`CAMERA_LOST`, `camera`).

- [ ] **Step 3: Implement**

- `RejectionReason`: append `CAMERA_LOST` after `BAD_COURT` with KDoc: "The pass followed the camera and did not know where it was at this frame (design §4.4). The frame is not mapped through the fixed marks, which is exactly the wrong answer when the camera has moved. Outside the gate order, like BAD_COURT: it is about the frame, not any person." Note in `worse()` nothing changes: `CAMERA_LOST` never enters it, because the frame is rejected before any person is examined.
- `PlayerPose`: add `val camera: Matrix3x3? = null` with KDoc: "This frame's camera, marking-frame pixels to these keypoints' pixels, when the pass followed the camera; null when it did not. A court reading of these keypoints goes through `courtFitAt(h, camera)`."
- `selectPlayers`: inside the frame loop, after `framesWithPose++`:

```kotlin
        val camera = raw.camera
        val m = if (camera == null) null else camera.at(frame.timestamp)
        if (camera != null && m == null) {
            for (side in CourtSide.entries) {
                val counts = rejections.getValue(side)
                counts[RejectionReason.CAMERA_LOST] = (counts[RejectionReason.CAMERA_LOST] ?: 0) + 1
            }
            continue
        }
        val back = m?.let { invert3x3(it) }
        val originals = frame.persons.map { it.toPosePerson() }
        val inMarkingFrame = if (back == null) originals else originals.map { p ->
            p.copy(keypoints = p.keypoints.map { k -> back.apply(k.x, k.y) ?: Point(Double.NaN, Double.NaN) })
        }
        val poseFrame = PoseFrame(frame.frame, inMarkingFrame)
```

  and when a person is selected, look up its original by index (`inMarkingFrame.indexOf(result.person)` is identity-based on data classes and can collide; instead iterate with indices: change the selection to remember the chosen index, e.g. add `val personIndex: Int = -1` to `NearPlayerSelector.Result` set in `select`) and build `PlayerPose(frame.frame, frame.timestamp, originals[i].keypoints, originals[i].keypointConfidence, m)`. A keypoint mapped to NaN has confidence untouched; the ankle check in `groundPoint` uses confidence, so also guard `groundPoint` against a NaN coordinate (return null).
- `LocalAnalysisCoordinator` and `CloudPoseCoordinator`: after selection, when any side's `rejections[CAMERA_LOST]` is positive, log `"camera: ${n} of ${framesWithPose} pose frames not followed; no positions taken for them"`, and when `raw.camera` is non-null log `"camera: followed with ${raw.camera.samples.size} samples"`. (`CloudPoseCoordinator` has an injected log or none; if none, add a `log: (String) -> Unit = {}` constructor parameter and pass the app's logger where it is constructed on both platforms.)
- Swift call sites: add `camera: nil` to each `PlayerPose(...)`.

- [ ] **Step 4: Run to see them pass**

Run: `./gradlew :analysis:jvmTest :analysis:iosSimulatorArm64Test :shared:jvmTest` (the shared JVM test task name per `shared/build.gradle.kts`), then build iOS tests: the iOS test target must still compile.
Expected: PASS, including every pre-existing selection test unchanged.

- [ ] **Step 5: Commit**

```bash
git add analysis/src shared/src iosApp/Sources/LocalAnalysis/SkeletonStore.swift iosApp/Tests
git commit -m "feat(analysis): selection maps each frame through the camera it was taken with"
```

---

### Task 8: The marking time travels with the marks, and the engine is told it

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/model/CourtKeypoints.kt` (add `markedAtSeconds`)
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/localvideo/court/CourtMarkingModel.kt` (`CourtMarkingState` carries `markedAtSeconds`; `toCourtKeypoints` writes it; `restored` keeps it)
- Modify: Android `androidApp/.../localvideo/court/CourtMarkingScreen.kt:775-801` (`CourtFrame` gains `markedAtSeconds = 0.1`) and wherever `CourtMarkingState(...)` is created from a `CourtFrame` (`CourtMarkingViewModel.kt`)
- Modify: iOS `CourtFrameLoader.swift:19-54` (return `actualTime` seconds from `generator.image(at:)`), `CourtMarkingView.swift` (pass it into `CourtMarkingState`)
- Modify: `shared/src/commonMain/kotlin/com/badmintontracker/shared/local/LocalInferenceEngine.kt` (`run(videoPath, markingTimeS: Double?, onProgress)`)
- Modify: `shared/.../local/LocalAnalysisCoordinator.kt` (resolve `T`; constructor gains `legacyMarkingTime: suspend (String) -> Double = { 0.1 }`)
- Modify: every `LocalInferenceEngine` implementation and fake: `AndroidLocalInferenceEngine.kt`, `IosLocalInferenceEngine.swift` (both `__run` and `rawInference`), `LocalAnalysisCoordinatorTest.kt` fake, iOS test fakes
- Modify: iOS `LocalAnalysisRunner.swift:468` (construct the coordinator with a `legacyMarkingTime` closure that runs the marking screen's generator with its exact tolerances on the video and returns `actualTime` seconds, 0.1 on failure)
- Test: `shared/src/commonTest/.../model/CourtKeypointsTest.kt` (create if absent), `shared/src/commonTest/.../localvideo/court/CourtMarkingModelTest.kt`, `shared/src/commonTest/.../local/LocalAnalysisCoordinatorTest.kt`, iOS `CourtTapMathTests.swift` or a new `CourtFrameLoaderTests.swift`

**Interfaces:**
- Consumes: nothing new from `:analysis`.
- Produces: `CourtKeypoints.markedAtSeconds: Double? = null` (`@SerialName("marked_at_s")`); `CourtMarkingState.markedAtSeconds: Double?`; `LocalInferenceEngine.run(videoPath: String, markingTimeS: Double?, onProgress: (Float) -> Unit): RawInference`; the coordinator passes `markingTimeS = keypoints.markedAtSeconds ?: legacyMarkingTime(videoPath)`.

- [ ] **Step 1: Write the failing tests**

- `CourtKeypointsTest`: JSON without `marked_at_s` decodes with `markedAtSeconds == null`; JSON with `"marked_at_s": 1.0` decodes to 1.0; encoding a value with `markedAtSeconds = null` produces no `marked_at_s` key (use the same `Json` the Postgrest client uses: default `Json`); encoding 0.1 produces `"marked_at_s":0.1`.
- `CourtMarkingModelTest`: `CourtMarkingState(w, h, markedAtSeconds = 1.0)` placed to completion gives `toCourtKeypoints().markedAtSeconds == 1.0`; `restored(w, h, keypointsWithMarkedAt(0.5))` keeps 0.5 even if the state was built for a frame at 0.1 (the saved marks belong to their own frame).
- `LocalAnalysisCoordinatorTest`: the fake engine records the `markingTimeS` it was given; with marks carrying 1.0 it is 1.0; with marks carrying null and a `legacyMarkingTime` returning 0.7 it is 0.7; with the default it is 0.1.
- iOS: a test that `CourtFrameLoader.loadFirstFrame` on a generated video (see memory `emulator-sim-login-recipe` for the ffmpeg recipe; an existing test helper `TestVideo` writes one) returns a `markedAtSeconds` equal to the generator's `actualTime`, and that the value reaches `CourtMarkingState`.

- [ ] **Step 2: Run to see them fail**

Run: `./gradlew :shared:jvmTest` (the module's JVM test task) and the iOS unit tests.
Expected: compilation failures.

- [ ] **Step 3: Implement**

- `CourtKeypoints`: add last `@SerialName("marked_at_s") val markedAtSeconds: Double? = null` with KDoc: "When in the video the marks were drawn, seconds. The camera tracker anchors to the first frame at or after it (design §3). Null on marks written before 2026-09-27 and by the web app, both of which mean 0.1 s, except legacy iOS marks, which the iOS runner recovers." Check `toAnalysis()` in `CourtKeypointsBridge.kt` ignores it (the analysis type has no time).
- `CourtMarkingState`: add `val markedAtSeconds: Double? = null` (last), `toCourtKeypoints()` sets it, `restored()` takes the saved keypoints' own value (not the new frame's).
- Android `CourtFrame`: add `val markedAtSeconds: Double = 0.1` with a comment that `OPTION_CLOSEST` does not report which frame it returned and 0.1 is within one frame; pass it where the state is built.
- iOS `CourtFrameLoader`: use `let (cgImage, actual) = try await generator.image(at: time)` and return `CMTimeGetSeconds(actual)` in the frame; keep both tolerances as they are (design §3). Pass it into the state.
- `LocalInferenceEngine.run` gains `markingTimeS: Double?` between `videoPath` and `onProgress`; KDoc: "When in the video the court was marked; the engine follows the camera from that frame when it runs pose. Null: do not follow the camera."
- Coordinator: constructor param `legacyMarkingTime`, compute `val markingTimeS = keypoints.markedAtSeconds ?: legacyMarkingTime(videoPath)` and pass it to `engine.run`.
- Android engine: accept the parameter and store it for Task 9 (unused for now, `@Suppress("UNUSED_PARAMETER")` is not wanted; keep it in a local `val` used by Task 9, or implement Task 9 immediately after).
- iOS engine: both entry points accept and forward it.
- iOS runner: the `legacyMarkingTime` closure.

- [ ] **Step 4: Run to see them pass**

Run: `./gradlew :shared:jvmTest :androidApp:testDebugUnitTest` and the iOS unit tests.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shared androidApp iosApp
git commit -m "feat: the court marks remember which moment they were drawn on"
```

---

### Task 9: Android follows the camera during the pass

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/AndroidLocalInferenceEngine.kt:72-149`
- Create: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/LumaPlane.kt`
- Test: `androidApp/src/test/java/com/badmintontracker/android/localanalysis/LumaPlaneTest.kt`; `androidApp/src/androidTest/java/com/badmintontracker/android/localanalysis/CameraTrackingTimingTest.kt`

**Interfaces:**
- Consumes: `CameraMotionTracker`, `Luma` (Tasks 1, 4), `RawInference.camera` (Task 6), `markingTimeS` (Task 8).
- Produces: `internal class LumaPlane { fun downsample(image: android.media.Image, outWidth: Int, outHeight: Int): GreyImage }` (reuses one `ByteArray` across frames).

- [ ] **Step 1: Write the failing tests**

`LumaPlaneTest` (JVM unit test with a fake plane): expose a pure function `lumaFrom(buffer: java.nio.ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int, scratch: ByteArray, outWidth: Int, outHeight: Int): GreyImage` and test it with a row stride larger than the width (padding ignored) and `pixelStride = 1`; assert `pixelStride != 1` throws `IllegalArgumentException` with a message naming the stride (YUV_420_888 guarantees 1 for Y; anything else is a decoder this code has not seen).

`CameraTrackingTimingTest` (androidTest): run `VideoFrameSource` over the corpus video bundled for androidTests (the same one `StageTimingTest` uses) for 300 frames, calling `LumaPlane.downsample` and `CameraMotionTracker.offer` when `wants`, and log the mean ms per sample and per frame with `Log.i("CameraTiming", ...)`. Assert only that `finish()` is non-null.

- [ ] **Step 2: Run to see them fail**

Run: `./gradlew :androidApp:testDebugUnitTest --tests '*LumaPlaneTest*'`
Expected: compilation failure.

- [ ] **Step 3: Implement**

- `LumaPlane.kt`: `image.planes[0]`: `buffer`, `rowStride`, `pixelStride`; copy `rowStride * (height - 1) + width` bytes from the buffer's position into the reused scratch array (grow it if needed), then `Luma.downsample(scratch, 0, image.width, image.height, rowStride, outWidth, outHeight)`. Restore the buffer's position after reading (other readers of the same `Image` in this frame use absolute indexing, but do not rely on it).
- Engine: when `pose != null` and `markingTimeS != null`, create `val tracker = CameraMotionTracker(markingTimeS, meta.width, meta.height)` and one `LumaPlane` before `track(...)`. In `onFrame`, before the detector, `if (tracker.wants(seconds)) tracker.offer(seconds, luma.downsample(image, tracker.trackerWidth, tracker.trackerHeight))`. Wrap the tracker call in `runCatching`; on failure log once and stop offering (a tracker failure never fails the run, design §5.5). After the pass, `RawInference(..., camera = tracker?.finish())` and log `"camera: ${tracker.acceptedSamples} samples followed, ${tracker.droppedSamples} dropped"`.

- [ ] **Step 4: Run to see them pass**

Run: `./gradlew :androidApp:testDebugUnitTest` then, on the emulator (`ANDROID_SERIAL=emulator-5554`), `./gradlew :androidApp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.badmintontracker.android.localanalysis.CameraTrackingTimingTest` and read `adb logcat -d -s CameraTiming`.
Expected: PASS; record the emulator ms per sample and per frame in the commit message, labelled as emulator figures.

- [ ] **Step 5: Commit**

```bash
git add androidApp
git commit -m "feat(android): follow the camera during the analysis pass"
```

---

### Task 10: iOS follows the camera during the pass

**Files:**
- Modify: `shared/src/iosMain/kotlin/com/badmintontracker/shared/local/BulkInterop.kt` (add `lumaDownsample`)
- Modify: `iosApp/Sources/LocalAnalysis/IosLocalInferenceEngine.swift:111-203`
- Test: `shared/src/iosTest/.../local/BulkInteropTest.kt` (or the existing iOS-side test of BulkInterop), `iosApp/Tests/LocalInferenceEngineTests.swift`

**Interfaces:**
- Consumes: Tasks 1, 4, 6, 8.
- Produces: `fun BulkInterop.lumaDownsample(plane: CPointer<ByteVar>, width: Int, height: Int, rowStride: Int, outWidth: Int, outHeight: Int): GreyImage`.

- [ ] **Step 1: Write the failing tests**

- Kotlin iOS test: allocate a native buffer (`nativeHeap.allocArray<ByteVar>`) with a row stride larger than the width, fill a known pattern, and assert `lumaDownsample` equals `Luma.downsample` on the same bytes copied into a `ByteArray`.
- Swift: in `LocalInferenceEngineTests`, the existing engine test over a generated video gains an assertion that with pose on and `markingTimeS: 0.1` the returned `RawInference.camera` is non-nil and its `referenceTimestamp` is the first frame time ≥ 0.1; with `markingTimeS: nil` it is nil. If the engine test cannot run pose on the simulator, construct the tracker path directly: a test that runs `VideoFrameSource` + `BulkInterop.lumaDownsample` + `CameraMotionTracker` over the generated video and asserts `finish()` is non-nil.

- [ ] **Step 2: Run to see them fail**

Run: `./gradlew :shared:iosSimulatorArm64Test` and the iOS unit tests.
Expected: compilation failures.

- [ ] **Step 3: Implement**

- `BulkInterop.lumaDownsample`: `Luma.downsampleWith(width, height, outWidth, outHeight) { x, y -> plane[y.toLong() * rowStride + x].toInt() and 0xFF }`. KDoc: the plane is the locked `CVPixelBuffer`'s plane 0 (`CVPixelBufferGetBaseAddressOfPlane(buf, 0)`, `CVPixelBufferGetBytesPerRowOfPlane(buf, 0)`), read in place so the 2 MB plane never crosses the bridge.
- Engine: when `pose != nil` and `markingTimeS != nil`, `let tracker = CameraMotionTracker(markingTimeS:sourceWidth:sourceHeight:)`; in `onFrame`, `if tracker.wants(timestamp: seconds) { let img = BulkInterop.shared.lumaDownsample(plane: base.assumingMemoryBound(to: Int8.self), width: w, height: h, rowStride: bpr, outWidth: tracker.trackerWidth, outHeight: tracker.trackerHeight); tracker.offer(timestamp: seconds, image: img) }` with the plane read inside the existing buffer lock (`VideoFrameSource.swift:230` locks read-only before calling `body`). A thrown Kotlin exception must not fail the run: guard with the same pattern the engine uses for optional pose (log and stop offering). Pass `camera: tracker?.finish()` into `RawInference(...)`. Log accepted and dropped counts through the engine's logger.

- [ ] **Step 4: Run to see them pass**

Run: `./gradlew :shared:iosSimulatorArm64Test` and the iOS unit tests (per `ios-build-environment`).
Expected: PASS. Record the simulator ms per sample in the commit message, labelled as simulator figures.

- [ ] **Step 5: Commit**

```bash
git add shared/src/iosMain shared/src/iosTest iosApp
git commit -m "feat(ios): follow the camera during the analysis pass"
```

---

### Task 11: The skeleton store keeps the camera, and the Skeleton panel uses it

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/SkeletonStore.kt` (v4)
- Modify: `iosApp/Sources/LocalAnalysis/SkeletonStore.swift` (v4)
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localanalysis/SkeletonPanel.kt:118-143, 257-271`
- Modify: `iosApp/Sources/LocalAnalysis/SkeletonPanel.swift:28, 114-162, 194-214`
- Test: `androidApp/src/test/.../SkeletonStoreTest.kt`, `iosApp/Tests/AnalysisStoreTests.swift`, `iosApp/Tests/SkeletonPanelTests.swift`, an Android panel test if one exists for the metrics series (`SkeletonDecisionTest` is in shared; add a pure helper test there if the per-pose fit is extracted to shared)

**Interfaces:**
- Consumes: `PlayerPose.camera` (Task 7), `courtFitAt` (Task 1).
- Produces: store `VERSION = 4` written by `save` and `saveAll`; a shared helper `fun poseCourtFit(h: Matrix3x3?, pose: PlayerPose): Matrix3x3?` in `analysis/.../player/PoseMetrics.kt` returning `h?.let { courtFitAt(it, pose.camera) }`, used by both panels.

- [ ] **Step 1: Write the failing tests**

Android `SkeletonStoreTest`:
- `a_device_save_writes_version_4_and_round_trips_cameras` (poses with a camera come back with the same camera, bit for bit).
- `poses_without_cameras_write_hasCamera_0_and_no_camera_bytes` (file length equals the v3 formula plus 4 bytes).
- `a_cloud_saveAll_writes_version_4_with_both_tracks` (cameras preserved per track).
- `version_1_2_and_3_files_still_load_without_cameras` (keep the existing v1/v2/v3 compatibility tests; change their expectations only to add `camera == null`).
- `a_file_mixing_poses_with_and_without_cameras_is_refused_at_save` (`save` throws `IllegalArgumentException`: design §5.3's invariant).
- `another_format_version_is_refused` uses version 9 (unchanged) and `has()` accepts 4.

iOS `AnalysisStoreTests`: the same cases, plus `testTheBinaryLayoutMatchesAndroidsHeader` updated to assert version 4 and the `hasCamera` field's offset, and a byte-for-byte fixture shared with Android: write a v4 file from Kotlin in the Android test to `androidApp/src/test/resources/skeleton-v4.bin` once, check it in, and have the iOS test load the same bytes (copy it into `iosApp/Tests/Fixtures/` and add it to the test target in `project.yml`, then `xcodegen generate --spec iosApp/project.yml --project iosApp`).

Panel: a shared test of `poseCourtFit` (in `analysis` commonTest `PoseMetricsTest.kt`): with a pose whose camera shifts by (30, -12), `poseMetrics(pose.keypoints, pose.confidence, poseCourtFit(h, pose))` gives the same `stanceM` and `behindServiceLineM` as the unshifted pose under `h`. iOS `SkeletonPanelTests`: the loaded series uses per-pose fits (a stored skeleton with a panned pose gives the unpanned metric).

- [ ] **Step 2: Run to see them fail**

Run: `./gradlew :analysis:jvmTest --tests '*PoseMetricsTest*' :androidApp:testDebugUnitTest --tests '*SkeletonStoreTest*'` and the iOS tests.
Expected: failures.

- [ ] **Step 3: Implement**

- Layout (both platforms, byte-identical): `"SKEL"`, `i32 4`, `f64 fps`, `i32 width`, `i32 height`, `i32 hasMarks`, 12 × (`f64`, `f64`), `i32 hasCamera`, `i32 trackCount`, per track `i32 side ordinal`, `i32 poseCount`, then per pose the existing record, followed when `hasCamera == 1` by `h00 h01 h02 h10 h11 h12 h20 h21` as `f64`.
- `save(entryId, poses, ...)` writes one NEAR track in v4. `saveAll` writes every track in v4. Both compute `hasCamera` from the poses: all have one (1), none (0), or a mix (throw).
- `has()` accepts 1, 2, 3, 4. `load` reads 1 and 2 as today, 3 as today, 4 with the camera block, and requires the exact remaining length (poses × (216 + 64 when hasCamera)).
- Update both stores' header KDoc with the v4 layout, and say save now writes v4 and why (a device run's poses carry cameras too).
- `poseCourtFit` in `PoseMetrics.kt`. Android panel: the residual gate still decides whether `h` is usable; then `MetricSample(it.timestamp, poseMetrics(it.keypoints, it.confidence, poseCourtFit(homography, it)))` and the per-frame `remember(pose, homography) { poseMetrics(pose.keypoints, pose.confidence, poseCourtFit(homography, pose)) }`. iOS: the same two sites, through `PoseMetricsKt.poseCourtFit(h:pose:)`.

- [ ] **Step 4: Run to see them pass**

Run: `./gradlew :analysis:jvmTest :androidApp:testDebugUnitTest` and the iOS unit tests.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add analysis androidApp iosApp
git commit -m "feat: the skeleton keeps each frame's camera, and its court readings use it"
```

---

### Task 12: The cloud's camera tracker

Repo: `/Users/coenhallie/Desktop/projects/badminton-tracker`, branch `feat/camera-motion`.

**Files:**
- Create: `backend/camera_motion.py`
- Create: `backend/scripts/verify_camera_motion.py`

**Interfaces:**
- Produces:
  - `class CameraMotionTracker: __init__(self, marking_time_s: float, source_width: int, source_height: int)`, `wants(self, timestamp: float) -> bool`, `offer(self, timestamp: float, frame_bgr) -> None` (does the grey conversion and `INTER_AREA` resize itself), `latest(self) -> numpy 3x3 | None` (the last accepted source-pixel `M`, for the causal in-loop use), `finish(self) -> CameraMotion | None`, `accepted`, `dropped`.
  - `class CameraMotion: reference_timestamp: float, samples: list[tuple[float, numpy 3x3]]`, `at(self, t) -> numpy 3x3 | None` with §4.4's rule.
  - `def marking_time_from(keypoints: dict | None) -> float` returning `keypoints["marked_at_s"]` when it is a finite number, else 0.1.

- [ ] **Step 1: Write the verify script (it is this repo's test)**

`backend/scripts/verify_camera_motion.py`, in the style of `verify_raw_inference_codec.py` (plain asserts, prints PASS/FAIL, exits non-zero on failure, no network):
1. Synthetic: a textured 1920x1080 image (seeded numpy noise, Gaussian-blurred), warped with `cv2.warpPerspective` by a known slow pan over 3 s at 30 fps; the tracker's `at(t)` recovers each frame's `M` to within 2 px at the four source corners.
2. Sampling: the same timestamps as the Kotlin `variable_frame_rate_samples_by_time_not_by_count` test give the same sample list `[0.0, 0.1, 0.21, 0.33, 0.41, 0.52]`.
3. `at()` rules: the same seven cases as Kotlin's `CameraMotionTest`.
4. `marking_time_from`: missing, `None`, a string, `NaN` give 0.1; `1.0` gives 1.0.
5. When `~/shuttl-corpus/IMG_5892/opencv_motion.json` exists: run the tracker over `source.mov` (decoded with `cv2.VideoCapture`, timestamps from `CAP_PROP_POS_MSEC` read AFTER `read()` here, to match the phone's clock for the comparison) and assert the per-sample worst-corner distance to `opencv_motion.json` has median ≤ 1 px.

- [ ] **Step 2: Run to see it fail**

Run: `cd /Users/coenhallie/Desktop/projects/badminton-tracker && PYTHONDONTWRITEBYTECODE=1 python3 backend/scripts/verify_camera_motion.py`
Expected: ImportError on `camera_motion`.

- [ ] **Step 3: Implement `backend/camera_motion.py`**

The §4 algorithm with the OpenCV calls §4 names, the Global Constraints' numbers, `cv2` imported inside functions (a module-level `import cv2` breaks offline scripts that import the processor without OpenCV installed; mirror the processor's existing lazy import). Grey from BGR with `cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)` then `cv2.resize(grey, (w, h), interpolation=cv2.INTER_AREA)`. The 10 px corner border is applied after `goodFeaturesToTrack`. Acceptance, pending-before-marking handling (tracked nearest-first backwards, then identity for the forward pass), pixel-centre `S`, and `h22 = 1` normalisation all as in the Kotlin tracker (Task 4). Docstrings cite the mobile design doc by path and section.

- [ ] **Step 4: Run to see it pass**

Run: `PYTHONDONTWRITEBYTECODE=1 python3 backend/scripts/verify_camera_motion.py`
Expected: PASS (and the corpus section runs, since the corpus exists on this Mac).

- [ ] **Step 5: Commit**

```bash
git add backend/camera_motion.py backend/scripts/verify_camera_motion.py
git commit -m "feat(backend): follow the camera from the court-marking frame"
```

---

### Task 13: The cloud writes RawInference version 2

Repo: `badminton-tracker` for the writer; `badminton-rally-mobile` for the fixture and its test.

**Files:**
- Modify: `badminton-tracker/backend/raw_inference_codec.py` (VERSION 2, camera section, `camera` parameter on `encode_raw_inference`)
- Modify: `badminton-tracker/backend/modal_supabase_processor.py:214-313` (`raw_inference_from_skeleton_frames(..., camera=None)` passes it through to the returned structure)
- Modify: `badminton-tracker/backend/scripts/verify_raw_inference_codec.py` (layout and exact-length checks for v2 with and without a camera; `--fixture PATH` writes a v2 fixture with a two-sample camera)
- Create: `badminton-rally-mobile/analysis/src/commonTest/resources/raw/cloud-poses-v2.rawi`
- Modify: `badminton-rally-mobile/analysis/src/commonTest/kotlin/com/badmintontracker/analysis/raw/RawInferenceCodecTest.kt` (a `the_python_v2_writer_and_this_reader_agree` test)

**Interfaces:**
- Consumes: `CameraMotion` (Task 12), the Kotlin codec (Task 6).
- Produces: `encode_raw_inference(..., camera: CameraMotion | None = None)`; the v1 fixture `cloud-poses.rawi` stays, frozen (the Python can no longer write v1; say so in the test's comment).

- [ ] **Step 1: Write the failing checks**

In `verify_raw_inference_codec.py`: the version field is 2; a stream without a camera ends with a single `0x00` after the frames and its exact length is the v1 formula + 1; a stream with a camera of N samples is the v1 formula + 1 + 8 + 4 + N × 72; the camera section's values read back with `struct.unpack_from` at the computed offsets.

In `RawInferenceCodecTest.kt`:

```kotlin
    @Test fun the_python_v2_writer_and_this_reader_agree() {
        val raw = RawInferenceCodec.decode(readResource("raw/cloud-poses-v2.rawi"))
        raw.header.version shouldBe 2
        val camera = raw.camera.shouldNotBeNull()
        camera.samples.size shouldBe 2
        // Values written by verify_raw_inference_codec.py --fixture; copy them from the script.
    }
```

Fill the expected numbers from the script's fixture definition, as the existing v1 test does.

- [ ] **Step 2: Run to see them fail**

Run: `PYTHONDONTWRITEBYTECODE=1 python3 backend/scripts/verify_raw_inference_codec.py` (in badminton-tracker) and `./gradlew :analysis:jvmTest --tests '*RawInferenceCodecTest*'` (in the mobile repo).
Expected: FAIL.

- [ ] **Step 3: Implement**

Writer per the Global Constraints layout. Then generate the fixture:

Run: `PYTHONDONTWRITEBYTECODE=1 python3 backend/scripts/verify_raw_inference_codec.py --fixture /Users/coenhallie/Desktop/projects/badminton-rally-mobile/analysis/src/commonTest/resources/raw/cloud-poses-v2.rawi`

- [ ] **Step 4: Run to see them pass**

Both commands from Step 2. Expected: PASS.

- [ ] **Step 5: Commit, one per repo**

```bash
# badminton-tracker
git add backend/raw_inference_codec.py backend/modal_supabase_processor.py backend/scripts/verify_raw_inference_codec.py
git commit -m "feat(backend): the pose artifact carries the camera (RawInference version 2)"
# badminton-rally-mobile
git add analysis/src/commonTest/resources/raw/cloud-poses-v2.rawi analysis/src/commonTest/kotlin/com/badmintontracker/analysis/raw/RawInferenceCodecTest.kt
git commit -m "test(analysis): the cloud's version 2 pose file reads on the phone"
```

---

### Task 14: The cloud loop follows the camera

Repo: `badminton-tracker`.

**Files:**
- Modify: `backend/modal_supabase_processor.py`: the image definition near `:3928` (mount `camera_motion.py`), `_run_full_yolo_loop` `:2819-3788` (tracker, shadows, shuttle area), the return into `tracking_state`, and the artifact build `:5121-5129`
- Create: `backend/scripts/run_phase2_locally.py` (the §8.6 harness)

**Interfaces:**
- Consumes: Tasks 12, 13.
- Produces: `tracking_state["camera_motion"]` (a `CameraMotion` or None); `raw_inference_from_skeleton_frames(..., camera=tracking_state.get("camera_motion"))`.

- [ ] **Step 1: Write the harness first (it is the failing test)**

`backend/scripts/run_phase2_locally.py` runs `_run_full_yolo_loop` on a local video on this Mac without Modal: it monkeypatches the `/cache` and `/models` paths the loop hard-codes (`:2879`, `:2985`) to a temp dir and `tools`' weights (`/Users/coenhallie/Desktop/projects/badminton-rally-mobile/tools/models/weights/yolo26m-pose.pt` and the detector `badminton.pt`), supplies a no-op `send_log`, loads `marks_ideal.json` from the corpus as `manual_court_keypoints`, runs the loop with the device set to `mps` if the loop exposes it (else CPU), then builds the artifact exactly as `:5121-5129` does and writes `poses.raw` to `~/shuttl-corpus/IMG_5892/cloud-local/poses.raw`. It prints frames processed, the camera's accepted and dropped counts, and how many frames kept a near-side player.

Read `_run_full_yolo_loop`'s signature and the Phase 2 caller (`_process_analytics_worker`, `:4682+`) first; call the loop the way the worker does, with the Phase 1 values the worker reads from `results.json` supplied from a probe of the video (`fps`, `total_frames`).

Run it before implementing: it runs, and the camera counts line is absent. That is the failing state.

- [ ] **Step 2: Implement**

- Image: add `.add_local_file(Path(__file__).parent / "camera_motion.py", "/root/camera_motion.py")` next to the `raw_inference_codec.py` line, matching its exact form.
- Loop: construct `CameraMotionTracker(marking_time_from(manual_court_keypoints), width, height)` once before reading. As each frame is read (`:3132-3133` region), `if tracker.wants(pts): tracker.offer(pts, frame)`. Per frame, `M = tracker.latest()` (identity when None). Before the court filter (`:3437`), build shadow skeletons: for each skeleton dict `s`, a shallow copy whose `center`, `bbox` and `kpts` are mapped by `M⁻¹` (`cv2.perspectiveTransform`), with `s_shadow["_orig"] = s`. Run the court filter, sitting filter, cumulative movement, candidate selection and `identity_tracker.match_skeletons` on the shadows, and emit `_orig` where the loop builds `frame_data["players"]` and `skeleton_frames`. Speed (`:3617-3628`) reads shadow centres (fixed `H_court` then correctly maps them). The Phase 2 shuttle court area (`:3063-3080`, used at `:3228`, `:3247`, `:3293`) is tested against the shuttle candidate mapped by `M⁻¹` into the marking frame (same idea, a point).
- After the loop: `tracking_state["camera_motion"] = tracker.finish()` and log accepted and dropped counts with `send_log`.
- Artifact: pass `camera=tracking_state.get("camera_motion")` through `raw_inference_from_skeleton_frames` and `encode_raw_inference`.
- Keep `verify_speed_filters.py`'s source regexes passing: do not rename or restructure `MAX_PX_PER_FRAME` or the `from speed_calc import` line.

- [ ] **Step 3: Run every verify script and the harness**

Run (in badminton-tracker):
```bash
for s in raw_inference_codec camera_motion tracker_invariants speed_filters rally_bounds annotation_shift; do PYTHONDONTWRITEBYTECODE=1 python3 backend/scripts/verify_$s.py || echo "FAIL $s"; done
PYTHONDONTWRITEBYTECODE=1 python3 backend/scripts/run_phase2_locally.py
```
Expected: every script PASS; the harness prints camera counts with 0 dropped on `IMG_5892`.

- [ ] **Step 4: Decode and select on the phone side**

In the mobile repo, add a corpus-gated jvmTest `CloudLocalRunCorpusTest.kt` in `analysis/src/jvmTest/.../camera/`: read `~/shuttl-corpus/IMG_5892/cloud-local/poses.raw`, decode, `selectPlayers(raw, marks_ideal)`, and compare the NEAR track with `reference_tracks.json`'s `ref_track`. Join by frame, not by timestamp: both decode sequentially from the start, so cloud frame `k` (1-based) is reference index `k - 1`. The cloud's timestamps run one frame late (design §3), so a timestamp join would pair each frame with its neighbour. Pass: at least 95% of pose frames produce a position; median ≤ 8 cm, p95 ≤ 25 cm against the reference.

Run: `ANALYSIS_REQUIRE_CORPUS=1 ./gradlew :analysis:jvmTest --tests '*CloudLocalRunCorpusTest*' -i`
Expected: PASS.

- [ ] **Step 5: Commit, one per repo**

```bash
# badminton-tracker
git add backend/modal_supabase_processor.py backend/scripts/run_phase2_locally.py
git commit -m "feat(backend): Phase 2 decides on camera-stabilised skeletons and writes the camera"
# badminton-rally-mobile
git add analysis/src/jvmTest tools/models/camera_motion_probe.py
git commit -m "test(analysis): a local cloud run's pose file, selected on the phone, against the reference"
```

---

### Task 15: End to end on both phones, and the record

**Files:**
- Modify: `docs/plans/2026-09-27-camera-motion-design.md` (status "Implemented", a "What shipped differently" section, a "Verification, as run" section with every number)
- Create: `docs/screenshots/2026-09-27-camera-*.png`

- [ ] **Step 1: Android, device path**

Install on `emulator-5554` (Pixel_9a; memory `emulator-sim-login-recipe`; the S23 only if Coen has it on wireless debugging, memory `adb-wireless-to-the-phone`). Put `source.mov` on the device (`adb push` to `/sdcard/Movies/IMG_5892.mov` and `adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Movies/IMG_5892.mov`), add it through the app's Add flow, mark the court on the marking screen at the `marks_ideal.json` points (tap coordinates computed from the displayed image's scale; memory `adb-screenshot-runs-verify-each-step`: verify each step with a screenshot), and run an on-device analysis with pose. Pull `files/player-tracks/<entryId>.track` with `run-as` and compare with `reference_tracks.json` `ref_track` (join on frame index). Pass: ≥ 95% of pose frames produce a position; median ≤ 5 cm and p95 ≤ 15 cm (design §8.5). Note that finger-free taps still carry the marking screen's rounding; if the bound fails, first compare the stored marks with `marks_ideal.json`. Screenshot the heatmap, Base and Skeleton panels, light and dark. Read `adb logcat -d | grep -i camera` for the tracker's accepted/dropped counts and the timing line.

- [ ] **Step 2: iOS, device path**

The same on the iPhone 17 Pro simulator: add `source.mov` to the simulator's Photos (`xcrun simctl addmedia`), then drive the app with a throwaway XCUITest per memory `emulator-sim-login-recipe` ("Driving the iOS simulator"), including the marking taps; run the analysis; read the stored track from the app container (`xcrun simctl get_app_container ... data`); same pass criteria; same screenshots. Delete the throwaway test and regenerate the project before committing. Never sign the simulator out; never `defaults read` the app domain.

- [ ] **Step 3: Full test gates**

Run: `./gradlew :analysis:jvmTest :analysis:iosSimulatorArm64Test :shared:jvmTest :androidApp:testDebugUnitTest` (plus lint tasks the repo runs in CI), the iOS unit and UI tests, and every backend verify script. All green; any skip explained.

- [ ] **Step 4: Update the design doc**

Status "Implemented 2026-09-27"; "What shipped differently" (every deviation from this plan and why); "Verification, as run" (every number from Tasks 4, 5, 9, 10, 14, 15 with where it was measured: host, emulator, simulator, S23); the production check (§8.7) listed as the owner's step with the deploy command.

- [ ] **Step 5: Commit**

```bash
git add docs
git commit -m "docs: the camera design as shipped, with its measurements"
```

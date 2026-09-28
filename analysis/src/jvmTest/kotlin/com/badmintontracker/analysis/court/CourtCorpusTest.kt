package com.badmintontracker.analysis.court

import com.badmintontracker.analysis.geometry.COURT_KEYPOINT_POSITIONS
import com.badmintontracker.analysis.geometry.CourtKeypoints
import com.badmintontracker.analysis.geometry.Matrix3x3
import com.badmintontracker.analysis.geometry.Point
import com.badmintontracker.analysis.geometry.apply
import com.badmintontracker.analysis.geometry.calculateHomography
import com.badmintontracker.analysis.geometry.invert3x3
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.fail

/**
 * The court detector on the 81-frame corpus `tools/court/build_court_corpus.py`
 * built from real recordings (design §8.2): every label reproduced, keypoints
 * against the reference implementation `tools/court/court_probe.py`, accuracy
 * against references the method did not produce, corner completion under tap
 * error, and a 4K frame.
 *
 * BLOCKED on a corpus: skips loudly unless `~/shuttl-corpus/court-detect/`
 * (manifest.json, frames/NAME.png) exists; the accuracy check also reads
 * `~/shuttl-corpus/IMG_5892/H_ideal.json`. `ANALYSIS_REQUIRE_CORPUS=1` turns
 * the skip into a failure. Read straight off the filesystem, as
 * `CameraMotionCorpusTest` does: 82 full-resolution frames do not belong in
 * the checkout.
 *
 * Each check collects every violation before failing, so one run reports
 * the whole corpus. On a port-check failure the Kotlin stage values (lines,
 * families, quads, residual, last step) are printed next to the probe's
 * `stages` from the manifest, computed here from the `internal` stages so no
 * debugging hook lives in production code.
 */
class CourtCorpusTest {

    @Test
    fun every_label_is_reproduced() {
        if (!corpusPresent()) return
        val mismatches = corpus.frames.mapNotNull { f ->
            val finding = corpus.findings.getValue(f.name)
            if (finding.found == f.court) null
            else "${f.name} (${f.split}): labelled ${if (f.court) "court" else "none"}, " +
                "found=${finding.found} (${finding.reason}, residual ${finding.residualPx?.let { "%.3f".format(it) }})"
        }
        val courts = corpus.frames.count { it.court }
        println("labels: ${corpus.frames.size - mismatches.size} / ${corpus.frames.size} reproduced ($courts with a court)")
        for (split in listOf("tune", "held")) {
            val frames = corpus.frames.filter { it.split == split }
            val found = frames.count { it.court && corpus.findings.getValue(it.name).found }
            val rejected = frames.count { !it.court && !corpus.findings.getValue(it.name).found }
            println(
                "  $split: courts found $found / ${frames.count { it.court }}, " +
                    "none rejected $rejected / ${frames.count { !it.court }}"
            )
        }
        for (f in corpus.frames) {
            val finding = corpus.findings.getValue(f.name)
            println(
                "  %-12s %-4s label %-5s kotlin %-5s (%s) probe (%s) residual %s".format(
                    f.name, f.split, f.court, finding.found, finding.reason, f.probeReason,
                    finding.residualPx?.let { "%.3f".format(it) } ?: "-",
                )
            )
        }
        printTiming()
        if (mismatches.isNotEmpty()) fail("${mismatches.size} labels not reproduced:\n" + mismatches.joinToString("\n"))
    }

    @Test
    fun keypoints_match_the_reference_implementation() {
        if (!corpusPresent()) return
        data class Row(val name: String, val median: Double, val max: Double)
        val rows = ArrayList<Row>()
        val violations = ArrayList<String>()
        for (f in corpus.frames) {
            val reference = f.keypoints ?: continue
            val finding = corpus.findings.getValue(f.name)
            val ours = finding.keypoints
            if (ours == null) {
                violations.add("${f.name}: the probe accepts it, Kotlin does not (${finding.reason})")
                continue
            }
            val d = ours.points().zip(reference.points()).map { (a, b) -> dist(a, b) }
            val row = Row(f.name, median(d), d.max())
            rows.add(row)
            if (row.median > 1.0 || row.max > 3.0) {
                violations.add("${f.name}: median %.3f px, max %.3f px".format(row.median, row.max))
            }
        }
        println("port check against court_probe.py, ${rows.size} frames (per frame: median, max px over the 12 keypoints):")
        for (r in rows) println("  %-12s median %.4f  max %.4f".format(r.name, r.median, r.max))
        println("  worst five by max:")
        for (r in rows.sortedByDescending { it.max }.take(5)) {
            println("    %-12s median %.4f  max %.4f".format(r.name, r.median, r.max))
        }
        println(
            "  over all frames: median of medians %.4f px, worst max %.4f px"
                .format(median(rows.map { it.median }), rows.maxOf { it.max })
        )
        if (violations.isNotEmpty()) {
            val failing = violations.map { it.substringBefore(':') }.toSet()
            for (f in corpus.frames.filter { it.name in failing }) printStages(f)
            fail("${violations.size} frames off the reference implementation:\n" + violations.joinToString("\n"))
        }
    }

    @Test
    fun accuracy_against_independent_references() {
        if (!corpusPresent()) return
        val hIdealFile = File(home, "shuttl-corpus/IMG_5892/H_ideal.json")
        if (!hIdealFile.exists()) {
            skip("'$hIdealFile' is not present, so the IMG_5892 accuracy was not measured")
            return
        }
        val violations = ArrayList<String>()

        // IMG_5892's marking frame: the court position the found keypoints
        // give to a grid over the near half (and a margin past its lines),
        // against where H_ideal (fitted from per-line reference marks) puts
        // the same pixels. H_ideal maps image px to court metres.
        val hIdealInv = invert3x3(parseMatrix(readJson(hIdealFile).jsonObject.getValue("H")))
            ?: error("H_ideal.json's H is singular")
        val ref = corpus.references.getValue("img_5892_marking_frame").jsonObject
        val refFinding = CourtFinder.find(decode(ref.getValue("frame").jsonPrimitive.content.removeSuffix(".png")))
        val refKeypoints = refFinding.keypoints
        if (refKeypoints == null) {
            violations.add("m5892_ref: not found (${refFinding.reason})")
        } else {
            val pxToCourt = calculateHomography(refKeypoints.points(), COURT_KEYPOINT_POSITIONS)
                ?: error("no homography from the found keypoints")
            val errors = ArrayList<Double>()
            for (i in 0 until 9) for (j in 0 until 9) {
                val x = -0.5 + (6.6 - -0.5) * i / 8
                val y = 6.7 + (14.9 - 6.7) * j / 8
                val px = hIdealInv.apply(x, y) ?: continue
                val m = pxToCourt.apply(px.x, px.y) ?: continue
                errors.add(hypot(m.x - x, m.y - y))
            }
            val reference = keypointsOf(ref.getValue("keypoints").jsonObject)
            val kpDist = refKeypoints.points().zip(reference.points()).map { (a, b) -> dist(a, b) }
            val med = median(errors)
            println(
                "IMG_5892 marking frame: near-half error over %d grid points: median %.2f cm, p90 %.2f cm, max %.2f cm; "
                    .format(errors.size, med * 100, percentile(errors, 90.0) * 100, errors.max() * 100) +
                    "keypoints vs the reference marks: median %.2f px, max %.2f px".format(median(kpDist), kpDist.max())
            )
            if (errors.size != 81) violations.add("m5892_ref: only ${errors.size} of 81 grid points projected")
            if (med > 0.03) violations.add("m5892_ref: median %.2f cm > 3 cm".format(med * 100))
        }

        // Paris 2024: the marks a user placed by hand. The user labelled
        // near/far inconsistently, so each near/far pair (service line left,
        // service line right, centre) is matched either way round, whichever
        // puts the pair nearer.
        val paris = corpus.references.getValue("p743d_human").jsonObject
        val human = keypointsOf(paris.getValue("keypoints").jsonObject).points()
        val parisErrors = ArrayList<Double>()
        for (name in paris.getValue("frames").jsonArray.map { it.jsonPrimitive.content }) {
            val finding = corpus.findings.getValue(name)
            val ours = finding.keypoints?.points()
            if (ours == null) {
                violations.add("$name: not found (${finding.reason})")
                continue
            }
            val d = ArrayList<Double>()
            for (k in 0..5) d.add(dist(ours[k], human[k]))
            for ((a, b) in listOf(6 to 8, 7 to 9, 10 to 11)) {
                val straight = dist(ours[a], human[a]) + dist(ours[b], human[b])
                val swapped = dist(ours[a], human[b]) + dist(ours[b], human[a])
                if (straight <= swapped) {
                    d.add(dist(ours[a], human[a])); d.add(dist(ours[b], human[b]))
                } else {
                    d.add(dist(ours[a], human[b])); d.add(dist(ours[b], human[a]))
                }
            }
            println("Paris $name vs the hand marks: median %.2f px, max %.2f px".format(median(d), d.max()))
            parisErrors.addAll(d)
        }
        if (parisErrors.isNotEmpty()) {
            val med = median(parisErrors)
            println("Paris, all %d distances: median %.2f px, p90 %.2f px".format(parisErrors.size, med, percentile(parisErrors, 90.0)))
            if (med > 6.0) violations.add("Paris: median %.2f px > 6 px".format(med))
        }
        if (violations.isNotEmpty()) fail("accuracy:\n" + violations.joinToString("\n"))
    }

    @Test
    fun four_corner_completion_survives_tap_error() {
        if (!corpusPresent()) return
        // One generator across every frame, trial and corner, drawn in that
        // order, x before y.
        val random = java.util.Random(3)
        val violations = ArrayList<String>()
        var passed = 0
        var total = 0
        val millis = ArrayList<Long>()
        for (name in listOf("hall_240", "m5892_2", "hk1_15", "cz_3", "dark_80", "par1_20", "b2eab_8", "hall_450")) {
            val detected = corpus.findings.getValue(name).keypoints
            if (detected == null) {
                violations.add("$name: find() found no court to tap the corners of")
                continue
            }
            val image = decode(name)
            repeat(3) { trial ->
                total++
                val taps = detected.corners.map { Point(it.x + TAP_SIGMA * random.nextGaussian(), it.y + TAP_SIGMA * random.nextGaussian()) }
                val tapError = taps.zip(detected.corners).maxOf { (a, b) -> dist(a, b) }
                val finding = CourtFinder.fromCorners(image, taps)
                millis.add(finding.millis)
                val completed = finding.keypoints
                if (completed == null) {
                    violations.add("$name trial $trial: not found (${finding.reason})")
                    println("  %-9s trial %d: worst tap %.1f px off, not found (%s)".format(name, trial, tapError, finding.reason))
                    return@repeat
                }
                val worst = completed.points().zip(detected.points()).maxOf { (a, b) -> dist(a, b) }
                println("  %-9s trial %d: worst tap %.1f px off, keypoints max %.4f px from find()".format(name, trial, tapError, worst))
                if (worst <= 2.0) passed++ else violations.add("$name trial $trial: max %.3f px".format(worst))
            }
        }
        println(
            "four-corner completion, tap error sigma %.0f px: %d / %d within 2 px; fromCorners ms median %.0f, max %d"
                .format(TAP_SIGMA, passed, total, median(millis.map { it.toDouble() }), millis.max())
        )
        if (violations.isNotEmpty()) fail("corner completion:\n" + violations.joinToString("\n"))
    }

    @Test
    fun four_k_frame_keypoints_are_in_source_pixels() {
        if (!corpusPresent()) return
        val hd = decode("m5892_2")
        val w = hd.width * 2
        val h = hd.height * 2
        val argb = IntArray(w * h) { i -> hd.argb[(i / w / 2) * hd.width + (i % w) / 2] }
        val fourK = CourtFinder.find(RgbImage(w, h, argb))
        val hdKeypoints = corpus.findings.getValue("m5892_2").keypoints ?: fail("m5892_2 not found at 1080p")
        val ours = fourK.keypoints ?: fail("m5892_2 upscaled to ${w}x$h: not found (${fourK.reason})")
        val d = ours.points().zip(hdKeypoints.points()).map { (a, b) -> dist(a, Point(2 * b.x + 0.5, 2 * b.y + 0.5)) }
        println(
            "4K (%dx%d, nearest 2x of m5892_2): found in %d ms; keypoints vs 1080p x 2 + 0.5: median %.6f px, max %.6f px"
                .format(w, h, fourK.millis, median(d), d.max())
        )
        if (median(d) > 1.5) fail("4K keypoints median %.3f px from the 1080p ones".format(median(d)))
    }

    // --- corpus -------------------------------------------------------------

    private class Frame(
        val name: String,
        val split: String,
        val court: Boolean,
        val probeReason: String,
        val stages: JsonObject,
        val keypoints: CourtKeypoints?,
    )

    private class Corpus(val frames: List<Frame>, val references: JsonObject) {
        /** `find` on every frame, each PNG decoded once, shared by the checks. */
        val findings: Map<String, CourtFinding> by lazy {
            frames.associate { f -> f.name to CourtFinder.find(decode(f.name)) }
        }
    }

    private companion object {
        const val TAP_SIGMA = 15.0
        val home: String = System.getProperty("user.home")
        val corpusDir = File(home, "shuttl-corpus/court-detect")
        val manifestFile = File(corpusDir, "manifest.json")

        val corpus: Corpus by lazy {
            val root = readJson(manifestFile).jsonObject
            val frames = root.getValue("frames").jsonArray.map {
                val o = it.jsonObject
                Frame(
                    name = o.getValue("name").jsonPrimitive.content,
                    split = o.getValue("split").jsonPrimitive.content,
                    court = o.getValue("court").jsonPrimitive.boolean,
                    probeReason = o.getValue("probe_reason").jsonPrimitive.content,
                    stages = o.getValue("stages").jsonObject,
                    keypoints = o["keypoints"]?.let { k -> keypointsOf(k.jsonObject) },
                )
            }
            Corpus(frames, root.getValue("references").jsonObject)
        }

        fun corpusPresent(): Boolean {
            if (manifestFile.exists()) return true
            skip(
                "corpus '$manifestFile' is not present, so the court detector was not measured on real " +
                    "frames. Run tools/court/build_court_corpus.py (it needs the source recordings)."
            )
            return false
        }

        fun skip(what: String) {
            val message = "SKIPPED: $what"
            if (System.getenv("ANALYSIS_REQUIRE_CORPUS") == "1") error(message)
            println(message)
        }

        fun decode(name: String): RgbImage {
            val file = File(corpusDir, "frames/$name.png")
            val image = ImageIO.read(file) ?: error("cannot decode '$file'")
            val w = image.width
            val h = image.height
            return RgbImage(w, h, image.getRGB(0, 0, w, h, null, 0, w))
        }

        fun readJson(file: File): JsonElement = Json.parseToJsonElement(file.readText())

        fun parseMatrix(json: JsonElement): Matrix3x3 =
            json.jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.double } }

        fun keypointsOf(o: JsonObject): CourtKeypoints =
            CourtKeypoints.fromMap(o.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.double } })
                ?: error("malformed keypoints: $o")

        fun printTiming() {
            val ms = corpus.frames.map { corpus.findings.getValue(it.name).millis.toDouble() }
            println(
                "find() over %d frames: total %.1f s, per frame median %.0f ms, max %.0f ms (the first frame carries the JIT warm-up: %.0f ms)"
                    .format(ms.size, ms.sum() / 1000, median(ms), ms.max(), ms.first())
            )
        }

        /**
         * The stage values `manifest.frames[].stages` records for the probe,
         * computed on the Kotlin side from the `internal` stages, for a frame
         * the port check fails on.
         */
        fun printStages(f: Frame) {
            val luma = decode(f.name).luma()
            val (ww, wh) = longSideSize(luma.width, luma.height, 960)
            val work = if (ww == luma.width) luma else areaDownsample(luma, ww, wh)
            val (fw, fh) = longSideSize(luma.width, luma.height, 1920)
            val fit = if (fw == luma.width) luma else areaDownsample(luma, fw, fh)
            val ridge = ridges(work)
            val lines = candidateLines(ridge)
            val families = lineFamilies(lines, ww, wh, horizon = false)
            val search = searchCourt(work, ridge)
            val refined = search?.let { refineCourt(fit, rescale(it.h, ww, wh, fw, fh), 12.0) }
            println(
                "  stages ${f.name}: probe ${f.stages}; kotlin lines=${lines.size} " +
                    "groups=[${families?.first?.size}, ${families?.second?.size}] quads=${search?.quads} " +
                    "rms=${refined?.rmsPx} step=${refined?.lastStepPx}"
            )
        }

        /** `h` (court metres to pixels of `fw x fh`) in pixels of `tw x th`, as `CourtFinder`'s `Frames` maps it. */
        fun rescale(h: DoubleArray, fw: Int, fh: Int, tw: Int, th: Int): DoubleArray {
            val sx = tw.toDouble() / fw
            val sy = th.toDouble() / fh
            val ox = 0.5 * sx - 0.5
            val oy = 0.5 * sy - 0.5
            val out = DoubleArray(9)
            for (c in 0..2) {
                out[c] = sx * h[c] + ox * h[6 + c]
                out[3 + c] = sy * h[3 + c] + oy * h[6 + c]
                out[6 + c] = h[6 + c]
            }
            val s = out[8]
            for (i in 0..8) out[i] /= s
            return out
        }

        fun CourtKeypoints.points(): List<Point> = listOf(
            topLeft, topRight, bottomRight, bottomLeft, netLeft, netRight,
            serviceLineNearLeft, serviceLineNearRight, serviceLineFarLeft, serviceLineFarRight,
            centerNear, centerFar,
        )

        fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

        fun median(values: List<Double>): Double {
            val s = values.sorted()
            val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
        }

        /** Linear-interpolated percentile, numpy's default. */
        fun percentile(values: List<Double>, p: Double): Double {
            val s = values.sorted()
            val rank = p / 100.0 * (s.size - 1)
            val lo = rank.toInt()
            val hi = minOf(lo + 1, s.size - 1)
            return s[lo] + (s[hi] - s[lo]) * (rank - lo)
        }
    }
}

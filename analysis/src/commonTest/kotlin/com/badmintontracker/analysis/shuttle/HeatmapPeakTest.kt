package com.badmintontracker.analysis.shuttle

import com.badmintontracker.analysis.corpus.corpusIsRequired
import com.badmintontracker.analysis.corpus.readResourceBytesOrNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test

/**
 * The heatmap postprocessing against production's own `_heatmap_to_coord`.
 *
 * Design section 5.4 originally accepted a Swift and Kotlin pair here and paid
 * for it with these vectors. Android first means there is no pair - this Kotlin
 * is what runs on the device - so the vectors are no longer arbitrating between
 * two implementations. They hold the single one to the Python, which is what
 * every other parity test in this module does.
 *
 * Regenerate with tools/models/make_shuttle_vectors.py.
 */
class HeatmapPeakTest {

    /**
     * Not 1e-6, and not chosen by loosening until green.
     *
     * numpy accumulates the weighted centroid in float32 and this accumulates
     * in Double, so the two disagree by float32 rounding. Measured across all
     * nine cases the worst difference is 7.6e-6 heatmap pixels, which at
     * 512x288 scaled to 1080p is 3e-5 source pixels.
     *
     * 1e-4 sits three orders of magnitude above that noise and three below any
     * real disagreement: picking a different blob or dropping the weighting
     * moves the answer by whole pixels, not by ten-thousandths. Matching
     * numpy's float32 summation instead would pin an implementation detail of
     * numpy rather than the algorithm.
     */
    private val TOLERANCE = 1e-4

    private class Case(val name: String, val heatmap: FloatArray, val width: Int, val height: Int)

    private fun load(): Pair<List<Case>, List<Triple<String, Boolean, Pair<Double, Double>>>>? {
        val bin = readResourceBytesOrNull("/shuttle/heatmaps.bin") ?: return null
        val json = readResourceBytesOrNull("/shuttle/expected.json")?.decodeToString() ?: return null

        var o = 0
        fun i32(): Int {
            val v = (bin[o].toInt() and 0xFF) or ((bin[o + 1].toInt() and 0xFF) shl 8) or
                ((bin[o + 2].toInt() and 0xFF) shl 16) or ((bin[o + 3].toInt() and 0xFF) shl 24)
            o += 4
            return v
        }
        val magic = bin.copyOfRange(0, 4).decodeToString(); o = 4
        require(magic == "SHUT") { "not a shuttle vector file: $magic" }
        require(i32() == 1) { "unsupported shuttle vector version" }
        val count = i32()

        val parsed = Json.parseToJsonElement(json).jsonObject
        val expected = parsed["cases"]!!.jsonArray.map {
            val o2 = it.jsonObject
            Triple(
                o2["name"]!!.jsonPrimitive.content,
                o2["visible"]!!.jsonPrimitive.boolean,
                o2["x"]!!.jsonPrimitive.double to o2["y"]!!.jsonPrimitive.double,
            )
        }
        val cases = (0 until count).map { idx ->
            val w = i32()
            val h = i32()
            val hm = FloatArray(w * h) {
                val bits = (bin[o].toInt() and 0xFF) or ((bin[o + 1].toInt() and 0xFF) shl 8) or
                    ((bin[o + 2].toInt() and 0xFF) shl 16) or ((bin[o + 3].toInt() and 0xFF) shl 24)
                o += 4
                Float.fromBits(bits)
            }
            Case(expected[idx].first, hm, w, h)
        }
        return cases to expected
    }

    @Test
    fun every_case_matches_productions_heatmap_to_coord() {
        val loaded = load()
        if (loaded == null) {
            val message = "SKIPPED: shuttle vectors absent; run tools/models/make_shuttle_vectors.py"
            if (corpusIsRequired()) error(message)
            println(message)
            return
        }
        val (cases, expected) = loaded

        // Every case, named in the failure. A count-only assertion would let a
        // rewrite that broke the area filter pass on the eight cases it still
        // got right.
        cases.forEachIndexed { i, case ->
            val (name, wantVisible, wantXy) = expected[i]
            val got = heatmapToCoord(case.heatmap, case.width, case.height)
            got.visible shouldBe wantVisible
            if (wantVisible) {
                val dx = abs(got.x - wantXy.first)
                val dy = abs(got.y - wantXy.second)
                if (dx > TOLERANCE || dy > TOLERANCE) {
                    throw AssertionError(
                        "$name: got (${got.x}, ${got.y}), wanted (${wantXy.first}, ${wantXy.second})"
                    )
                }
            }
        }
        // The fixture must actually contain the discriminating cases, so a
        // regeneration that dropped them cannot quietly narrow this.
        cases.size shouldBe 10
    }

    @Test
    fun the_median_truncates_the_way_numpy_does() {
        // Two frames, so numpy averages the middle pair and the median is
        // fractional. 10 and 11 average to 10.5, which astype(uint8)
        // truncates to 10; rounding would give 11 and shift the background on
        // roughly half the pixels of every even-sized sample.
        val out = medianBackground(listOf(byteArrayOf(10, 200.toByte()), byteArrayOf(11, 201.toByte())))
        (out[0].toInt() and 0xFF) shouldBe 10
        (out[1].toInt() and 0xFF) shouldBe 200
    }

    @Test
    fun an_odd_sample_takes_the_middle_value() {
        val out = medianBackground(
            listOf(byteArrayOf(5), byteArrayOf(200.toByte()), byteArrayOf(9))
        )
        (out[0].toInt() and 0xFF) shouldBe 9
    }
}

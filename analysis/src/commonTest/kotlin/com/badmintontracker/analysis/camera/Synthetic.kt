package com.badmintontracker.analysis.camera

import com.badmintontracker.analysis.geometry.Matrix3x3
import com.badmintontracker.analysis.geometry.apply
import com.badmintontracker.analysis.geometry.invert3x3
import kotlin.math.sin
import kotlin.random.Random

/** Test images with texture a tracker can hold on to, and known warps of them. */
object Synthetic {
    /** Two crossed sinusoids plus many smooth blobs: corners everywhere, no aliasing. */
    fun texture(width: Int, height: Int, seed: Int): GreyImage {
        val r = Random(seed)
        val blobs = List(250) { Triple(r.nextDouble() * width, r.nextDouble() * height, 2.5 + r.nextDouble() * 5.5) }
        val px = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            var v = 128.0 + 30 * sin(x * 0.11) * sin(y * 0.07) + 20 * sin(x * 0.23 + 1.0) * sin(y * 0.19 + 0.5)
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

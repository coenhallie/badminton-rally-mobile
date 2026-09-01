package com.badmintontracker.android.localanalysis

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer

/**
 * A thin ONNX Runtime wrapper.
 *
 * Deliberately thin, and deliberately not leaking ONNX types: section 8 keeps
 * a native LiteRT runtime as a designed-for escape hatch, and every
 * `OrtSession` that reaches a caller closes that hatch a little further.
 */
class OnnxSession(modelPath: String, provider: Provider = Provider.CPU) : Closeable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelPath, options(provider))

    /** Which execution provider to add, if any. */
    enum class Provider { CPU, NNAPI, XNNPACK }

    private companion object {
        /**
         * Execution providers, in the order they are worth trying.
         *
         * The first measurement on an S23 ran TrackNet at 216ms per frame on
         * the default CPU provider, which projects to tens of minutes for a
         * three-minute match. NNAPI hands supported subgraphs to the device's
         * accelerators; XNNPACK is the fallback that at least uses optimised
         * ARM kernels on the CPU.
         *
         * Both are attempted and both may decline: NNAPI silently falls back
         * per-operator, and a build without XNNPACK throws. Failing to add an
         * accelerator must not fail the session - a slow analysis beats none -
         * so each is tried independently and the outcome is recorded rather
         * than assumed.
         */
        fun options(provider: Provider): OrtSession.SessionOptions {
            val o = OrtSession.SessionOptions()
            o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // Adding a provider must never fail the session: a slow analysis
            // beats none, and both of these can legitimately decline.
            when (provider) {
                Provider.CPU -> Unit
                Provider.NNAPI -> runCatching { o.addNnapi() }
                Provider.XNNPACK -> runCatching { o.addXnnpack(emptyMap()) }
            }
            return o
        }
    }

    val inputName: String get() = session.inputNames.first()
    val outputName: String get() = session.outputNames.first()

    /** Static dimensions of the first input, with dynamic axes reported as -1. */
    fun inputShape(): LongArray =
        (session.inputInfo.getValue(inputName).info as ai.onnxruntime.TensorInfo).shape

    fun outputShape(): LongArray =
        (session.outputInfo.getValue(outputName).info as ai.onnxruntime.TensorInfo).shape

    /**
     * Run one inference.
     *
     * [shape] is taken from the caller rather than [inputShape] because
     * InpaintNet's length axis is genuinely dynamic: production chunks a
     * trajectory at 256 with stride 128, so the same session is fed several
     * lengths in one pass.
     */
    fun run(input: FloatArray, shape: LongArray): FloatArray {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val out = result[0].value
                return flatten(out)
            }
        }
    }

    /**
     * ONNX Runtime hands back arrays nested to the tensor's rank; flatten them
     * into one FloatArray without boxing.
     *
     * The obvious `flatMap { it.asIterable() }.toFloatArray()` boxes every
     * element. TrackNet's output is 1.2M floats per sequence at batch 1, so
     * that allocated 1.2M Float objects per inference; at batch 4 it exhausted
     * a 256MB heap outright. Copying plane by plane into a preallocated array
     * allocates once.
     */
    private fun flatten(value: Any?): FloatArray {
        val out = FloatArray(countFloats(value))
        var at = 0
        fun copy(v: Any?) {
            when (v) {
                is FloatArray -> { v.copyInto(out, at); at += v.size }
                is Array<*> -> v.forEach { copy(it) }
                else -> error("unexpected ONNX output type ${v?.let { it::class.simpleName }}")
            }
        }
        copy(value)
        return out
    }

    private fun countFloats(value: Any?): Int = when (value) {
        is FloatArray -> value.size
        is Array<*> -> value.sumOf { countFloats(it) }
        else -> error("unexpected ONNX output type ${value?.let { it::class.simpleName }}")
    }

    override fun close() {
        session.close()
    }
}

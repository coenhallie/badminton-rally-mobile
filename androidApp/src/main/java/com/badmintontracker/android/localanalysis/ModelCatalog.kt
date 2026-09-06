package com.badmintontracker.android.localanalysis

import android.content.Context
import com.badmintontracker.android.BuildConfig
import java.io.File

/**
 * The Phase 1 ONNX graphs, staged out of assets onto disk.
 *
 * ONNX Runtime wants a file path or a byte array; assets are neither until
 * they are extracted, so each model is copied to the app's files directory on
 * first use and reused after. They are already-compressed binaries, so the
 * copy costs disk rather than time.
 *
 * Pose is the NANO model. The medium one the cloud runs is 43.5MB, which is why
 * pose stayed out of the APK until now; nano is 6.3MB, smaller than the
 * detector, and at 230ms a frame against medium's 1567 it is the only one that
 * runs on a phone at all.
 */
enum class Model(val asset: String) {
    TRACKNET("models/tracknet.fp16.onnx"),
    /**
     * fp32, deliberately, while every other graph here is fp16.
     *
     * The fp16 conversion of this model is broken. Measured against the real
     * PyTorch module on trajectories shaped like production's chunks, the fp32
     * graph is within 0.05px at 512x288 and the fp16 graph is out by up to 76px
     * and returns NaN outright on a third of chunks at the 256 length
     * production actually uses. The 0a gate's new inpaint_raw term is what
     * caught it; see tools/models/README.md step 4.
     *
     * That matches the warning already recorded for the converter this graph
     * came from: onnxconverter-common fails at Resize nodes, and InpaintNet
     * upsamples. Costs 1MB over the fp16 file, which is the whole price of the
     * fix. Do not "restore consistency" by switching this back.
     */
    INPAINTNET("models/inpaintnet.onnx"),
    DETECTOR("models/badminton.fp16.onnx"),

    /**
     * yolo26n-pose at 960. Optional: only loaded when the user asks for a
     * metric that needs it, because pose roughly doubles a run.
     */
    POSE("models/posen.fp16.onnx"),
}

object ModelCatalog {

    /**
     * Identifies the weights these graphs came from.
     *
     * Read from the manifest rather than typed here: a hand-maintained string
     * that someone forgets to bump makes a weights change invisible, and
     * section 5.4's clip re-anchoring rule keys on exactly this value to decide
     * whether annotation timestamps must move.
     *
     * Phase 1 weights only, on purpose. Pose cannot move a clip boundary, so
     * folding it in would re-anchor every annotation in the library the first
     * time the pose model changed, for no reason.
     */
    val VERSION: String = BuildConfig.MODEL_VERSION

    fun path(context: Context, model: Model): String {
        val out = File(context.filesDir, "onnx/${model.asset.substringAfterLast('/')}")
        if (out.exists() && out.length() > 0) return out.path
        out.parentFile?.mkdirs()
        context.assets.open(model.asset).use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out.path
    }
}

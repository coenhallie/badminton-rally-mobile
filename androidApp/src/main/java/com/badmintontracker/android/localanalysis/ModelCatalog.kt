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
 * Phase 1 only. Pose belongs to Phase 2 and is deliberately not bundled: it is
 * 43.5MB against this whole set's 28.5MB.
 */
enum class Model(val asset: String) {
    TRACKNET("models/tracknet.fp16.onnx"),
    INPAINTNET("models/inpaintnet.fp16.onnx"),
    DETECTOR("models/badminton.fp16.onnx"),
}

object ModelCatalog {

    /**
     * Identifies the weights these graphs came from.
     *
     * Read from the manifest rather than typed here: a hand-maintained string
     * that someone forgets to bump makes a weights change invisible, and
     * section 5.4's clip re-anchoring rule keys on exactly this value to decide
     * whether annotation timestamps must move.
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

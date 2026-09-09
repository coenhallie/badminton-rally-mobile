package com.badmintontracker.android.localanalysis

import android.content.Context
import android.content.res.AssetManager
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
/**
 * A graph the app SHIPS. InpaintNet is not one: the stage it belongs to is not
 * run on either platform, so it is exported and tested but left out of the APK -
 * see `testOnlyModels` in androidApp/build.gradle.kts, and `path(Context, String)`
 * below for how the test still loads it.
 */
enum class Model(val asset: String) {
    TRACKNET("models/tracknet.fp16.onnx"),
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

    fun path(context: Context, model: Model): String = path(context, model.asset)

    /**
     * The same staging, for a graph named by its asset path rather than by a
     * [Model], and optionally read from somewhere other than [context]'s own
     * assets.
     *
     * Both exist for the one graph the app does not ship: `OnnxSessionTest`
     * reads InpaintNet out of the TEST apk's assets, so that the enum above can
     * go on meaning "what this APK carries". [assets] is a separate argument
     * because an instrumented test runs inside the APP's process - it can read
     * the test package's assets but cannot write to its data directory, so the
     * file has to be read from one package and staged into the other's.
     */
    fun path(context: Context, asset: String, assets: AssetManager = context.assets): String {
        val out = File(context.filesDir, "onnx/${asset.substringAfterLast('/')}")
        if (out.exists() && out.length() > 0) return out.path
        out.parentFile?.mkdirs()
        assets.open(asset).use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out.path
    }
}

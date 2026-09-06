package com.badmintontracker.shared.local

import com.badmintontracker.analysis.raw.RawInference

/**
 * The platform layer's whole contract, per section 5.1.
 *
 * An implementation decodes frames, runs models, and emits raw model output.
 * It never computes a metric, never assigns a player_id, never decides a rally
 * boundary. Everything with a history of drifting between implementations
 * lives in :analysis, written once.
 *
 * Injected rather than implemented behind expect/actual, following the
 * openChannel parameter on RallyApp.analyzeCoordinator: the platform supplies
 * the capability, and the shared code owns the sequencing. That is also what
 * lets the whole pipeline be exercised in CI against a fake.
 */
interface LocalInferenceEngine {
    /**
     * @param onProgress fraction in [0, 1) of the decode and inference pass.
     *   The coordinator owns reporting completion, so an engine must not
     *   report 1.0.
     */
    suspend fun run(videoPath: String, onProgress: (Float) -> Unit): RawInference
}

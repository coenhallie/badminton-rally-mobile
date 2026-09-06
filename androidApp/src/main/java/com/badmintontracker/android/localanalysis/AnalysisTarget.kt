package com.badmintontracker.android.localanalysis

/**
 * Which pipeline analyses a video.
 *
 * Both are offered side by side, deliberately and for as long as the local
 * pipeline is being trusted. Clip cutting is the app's whole output, so the
 * only convincing evidence that the on-device path is right is running the two
 * over the same video and comparing what comes back - which is the in-app
 * venue section 5.7 asks for, arrived at from the other direction.
 */
enum class AnalysisTarget(val label: String) {
    Cloud("Analyse in cloud"),
    Device("Analyse on device"),
}

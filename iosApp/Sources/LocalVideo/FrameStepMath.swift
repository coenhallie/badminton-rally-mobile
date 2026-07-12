import Foundation

/// Pure port of Android FrameStepBar.seekFrames (androidApp .../clipdetail/FrameStepBar.kt):
///   1. add half a frame before dividing so a truncated reported position still
///      maps to the frame that is actually displayed;
///   2. target 1ms past the frame's start so an exact seek lands inside the
///      frame's display interval, not on the boundary.
enum FrameStepMath {
    static func targetSeconds(
        currentSeconds: Double,
        fps: Float,
        delta: Int64,
        durationSeconds: Double
    ) -> Double {
        let effectiveFps = fps > 0 ? Double(fps) : 30.0
        let frameDur = 1.0 / effectiveFps
        let currentFrame = Int64((currentSeconds + frameDur / 2.0) / frameDur)
        let next = max(0, currentFrame + delta)
        let target = Double(next) * frameDur + 0.001
        let maxPos = durationSeconds > 0 ? durationSeconds : .greatestFiniteMagnitude
        return min(max(target, 0), maxPos)
    }
}

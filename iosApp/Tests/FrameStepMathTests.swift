import XCTest
@testable import iosApp

/// Port of Android FrameStepBar.seekFrames semantics (see the Kotlin source
/// for the two slack rules: half-frame index offset + land 1ms past the PTS).
final class FrameStepMathTests: XCTestCase {
    private let fps30Dur = 1.0 / 30.0

    func testStepForwardFromTruncatedPosition() {
        // Frame 1 at 30fps has PTS 33.333ms; a truncating player reports 0.033.
        // +1 must land in frame 2 (not re-seek frame 1 and get stuck).
        let target = FrameStepMath.targetSeconds(
            currentSeconds: 0.033, fps: 30, delta: 1, durationSeconds: 10
        )
        XCTAssertEqual(target, 2.0 * fps30Dur + 0.001, accuracy: 0.0001)
    }

    func testRepeatedForwardStepsNeverStick() {
        // Simulate a player that truncates positions to whole milliseconds.
        var position = 0.0
        var frames: [Int] = []
        for _ in 0..<5 {
            let target = FrameStepMath.targetSeconds(
                currentSeconds: position, fps: 30, delta: 1, durationSeconds: 60
            )
            frames.append(Int(target / fps30Dur))
            position = (target * 1000).rounded(.down) / 1000  // ms truncation
        }
        XCTAssertEqual(frames, [1, 2, 3, 4, 5])
    }

    func testBackwardClampsAtZero() {
        let target = FrameStepMath.targetSeconds(
            currentSeconds: 0, fps: 30, delta: -1, durationSeconds: 10
        )
        XCTAssertEqual(target, 0.001, accuracy: 0.0001)   // frame 0 + 1ms slack
    }

    func testForwardClampsAtDuration() {
        let target = FrameStepMath.targetSeconds(
            currentSeconds: 0.99, fps: 30, delta: 300, durationSeconds: 1.0
        )
        XCTAssertEqual(target, 1.0, accuracy: 0.0001)
    }

    func testFpsFallbackTo30() {
        let withZeroFps = FrameStepMath.targetSeconds(
            currentSeconds: 0.033, fps: 0, delta: 1, durationSeconds: 10
        )
        let with30Fps = FrameStepMath.targetSeconds(
            currentSeconds: 0.033, fps: 30, delta: 1, durationSeconds: 10
        )
        XCTAssertEqual(withZeroFps, with30Fps, accuracy: 0.000001)
    }
}

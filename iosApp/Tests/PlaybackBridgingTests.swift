import Shared
import XCTest

/// The playback preference lives in Kotlin, so every value the iOS bar shows
/// crosses the SKIE bridge as a boxed number. These lock that crossing down:
/// the bar itself cannot be exercised in a unit test, so a silent change in how
/// SKIE boxes `List<Int>` / `List<Float>` would otherwise reach users first.
final class PlaybackBridgingTests: XCTestCase {

    func testSkipOptionsBridgeToSwiftInts() {
        XCTAssertEqual(
            PlaybackOptions.shared.skipSecondsOptions.map { $0.intValue },
            [1, 2, 5, 10, 15, 30]
        )
    }

    func testSpeedOptionsBridgeToSwiftFloats() {
        XCTAssertEqual(
            PlaybackOptions.shared.speedOptions.map { $0.floatValue },
            [0.25, 0.5, 1.0, 1.5, 2.0]
        )
    }

    func testDefaultsMatchTheStatesTheBarInitialisesWith() {
        XCTAssertEqual(Int(PlaybackOptions.shared.DEFAULT_SKIP_SECONDS), 10)
        XCTAssertEqual(PlaybackOptions.shared.DEFAULT_SPEED, 1.0)
    }

    func testSpeedLabelsRenderTheSameTextAsAndroid() {
        let labels = PlaybackOptions.shared.speedOptions.map {
            PlaybackOptions.shared.formatSpeed(speed: $0.floatValue)
        }
        XCTAssertEqual(labels, ["0.25\u{00d7}", "0.5\u{00d7}", "1\u{00d7}", "1.5\u{00d7}", "2\u{00d7}"])
    }

    func testSkipMathClampsThroughTheMillisecondsTheBarPassesIt() {
        // The bar converts CMTime seconds to ms before calling in; a 10s skip
        // from 57s of a 60s clip must land on the end, not past it.
        XCTAssertEqual(
            SkipMath.shared.targetMs(positionMs: 57_000, deltaSeconds: 10, durationMs: 60_000),
            60_000
        )
        // An indefinite CMTime arrives as -1 and must not clamp the skip away.
        XCTAssertEqual(
            SkipMath.shared.targetMs(positionMs: 12_000, deltaSeconds: 10, durationMs: -1),
            22_000
        )
    }
}

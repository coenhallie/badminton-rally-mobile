import XCTest
@testable import iosApp

/// `PlaybackPosition.fraction`, which is what the scrub bar's width and the
/// progress fill are derived from.
///
/// Worth its own tests because every input it takes is one AVPlayer hands over
/// before it knows the answer: a duration of zero while the item loads, and a
/// position that can sit a few milliseconds past the end once playback settles.
/// Both would otherwise reach a `frame(width:)`.
final class PlaybackPositionTests: XCTestCase {

    func testAnUnknownDurationReadsAsNoProgressRatherThanDividingByZero() {
        // What the first frames after a screen opens actually carry.
        XCTAssertEqual(PlaybackPosition(positionMs: 0, durationMs: 0).fraction, 0)
        XCTAssertEqual(PlaybackPosition(positionMs: 1_200, durationMs: 0).fraction, 0)
    }

    func testProgressIsThePositionOverTheDuration() {
        XCTAssertEqual(PlaybackPosition(positionMs: 500, durationMs: 2_000).fraction, 0.25)
        XCTAssertEqual(PlaybackPosition(positionMs: 2_000, durationMs: 2_000).fraction, 1)
    }

    func testAPositionPastTheEndIsClamped() {
        // AVPlayer reports a position a little beyond the duration as playback
        // settles on the last frame; unclamped that is a fill wider than its bar.
        XCTAssertEqual(PlaybackPosition(positionMs: 2_050, durationMs: 2_000).fraction, 1)
        XCTAssertEqual(PlaybackPosition(positionMs: -30, durationMs: 2_000).fraction, 0)
    }

    func testADefaultPositionIsAtTheStart() {
        XCTAssertEqual(PlaybackPosition().fraction, 0)
    }
}

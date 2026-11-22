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

    func testVideoCardZoomClampsToViewportEdges() {
        let size = CGSize(width: 300, height: 200)
        // At 1× any offset snaps to zero.
        XCTAssertEqual(VideoCardZoom.clampedOffset(CGSize(width: 50, height: -40), scale: 1, size: size), .zero)
        // At 2× max travel is half the viewport size in each direction.
        let clamped = VideoCardZoom.clampedOffset(CGSize(width: 500, height: -500), scale: 2, size: size)
        XCTAssertEqual(clamped.width, 150, accuracy: 0.001)
        XCTAssertEqual(clamped.height, -100, accuracy: 0.001)
    }

    func testVideoCardDoubleTapOffsetKeepsCenterAndClampsCorners() {
        let size = CGSize(width: 300, height: 200)
        let centerOffset = VideoCardZoom.doubleTapOffset(
            tap: CGPoint(x: 150, y: 100),
            targetScale: VideoCardZoom.doubleTapScale,
            size: size
        )
        XCTAssertEqual(centerOffset.width, 0, accuracy: 0.001)
        XCTAssertEqual(centerOffset.height, 0, accuracy: 0.001)

        // Tapping top-left corner clamps to +maxX, +maxY.
        let cornerOffset = VideoCardZoom.doubleTapOffset(
            tap: CGPoint(x: 0, y: 0),
            targetScale: 2,
            size: size
        )
        XCTAssertEqual(cornerOffset.width, 150, accuracy: 0.001)
        XCTAssertEqual(cornerOffset.height, 100, accuracy: 0.001)
    }
}

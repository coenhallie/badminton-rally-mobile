import XCTest
@testable import iosApp

/// Port of Android CourtMarkingScreen's tap inverse-transform:
/// x = (tap.x - offset.x) / scale (origin top-left).
final class CourtTapMathTests: XCTestCase {
    func testIdentityAtScaleOne() {
        let p = CourtTapMath.inverse(tapX: 120, tapY: 40, offsetX: 0, offsetY: 0, scale: 1)
        XCTAssertEqual(p.x, 120, accuracy: 0.001)
        XCTAssertEqual(p.y, 40, accuracy: 0.001)
    }

    func testZoomedAndPanned() {
        // scale 2, content translated by (-100, -50): tap at (300, 250)
        // maps back to ((300 - (-100)) / 2, (250 - (-50)) / 2) = (200, 150).
        let p = CourtTapMath.inverse(tapX: 300, tapY: 250, offsetX: -100, offsetY: -50, scale: 2)
        XCTAssertEqual(p.x, 200, accuracy: 0.001)
        XCTAssertEqual(p.y, 150, accuracy: 0.001)
    }

    func testClampedPanOffsetKeepsCourtInsideBounds() {
        let size = CGSize(width: 300, height: 200)
        // Scale 1× always resets to zero.
        XCTAssertEqual(CourtMarkingView.clampedPanOffset(CGSize(width: -40, height: 25), scale: 1, displaySize: size), .zero)
        // Top-leading anchor: offset must stay in [-size*(scale-1), 0].
        let positive = CourtMarkingView.clampedPanOffset(CGSize(width: 50, height: 30), scale: 2, displaySize: size)
        XCTAssertEqual(positive.width, 0, accuracy: 0.001)
        XCTAssertEqual(positive.height, 0, accuracy: 0.001)

        let farNegative = CourtMarkingView.clampedPanOffset(CGSize(width: -999, height: -999), scale: 2, displaySize: size)
        XCTAssertEqual(farNegative.width, -300, accuracy: 0.001)
        XCTAssertEqual(farNegative.height, -200, accuracy: 0.001)
    }
}

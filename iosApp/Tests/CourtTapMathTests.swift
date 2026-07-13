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
}

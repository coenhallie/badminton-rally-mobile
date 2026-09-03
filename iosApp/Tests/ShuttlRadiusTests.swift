import XCTest
@testable import iosApp

/// The mirror of androidApp's ShuttlShapesTest. Same numbers, asserted on both
/// sides so the two clients cannot drift apart unnoticed.
final class ShuttlRadiusTests: XCTestCase {
    func testRadiusScaleMatchesTheDesign() {
        XCTAssertEqual(ShuttlRadius.extraSmall, 8)
        XCTAssertEqual(ShuttlRadius.small, 12)
        XCTAssertEqual(ShuttlRadius.medium, 16)
        XCTAssertEqual(ShuttlRadius.large, 20)
        XCTAssertEqual(ShuttlRadius.extraLarge, 28)
        XCTAssertEqual(ShuttlRadius.pill, 999)
    }
}

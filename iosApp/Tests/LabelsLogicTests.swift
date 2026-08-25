import XCTest
@testable import iosApp

final class LabelsLogicTests: XCTestCase {

    func testTappingANewRowExpandsIt() {
        XCTAssertEqual(LabelsLogic.nextExpanded(current: nil, tapped: "l1"), "l1")
        XCTAssertEqual(LabelsLogic.nextExpanded(current: "l1", tapped: "l2"), "l2")
    }

    func testTappingTheExpandedRowCollapsesIt() {
        XCTAssertNil(LabelsLogic.nextExpanded(current: "l1", tapped: "l1"))
    }
}

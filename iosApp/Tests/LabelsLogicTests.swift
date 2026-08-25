import XCTest
@testable import iosApp

final class LabelsLogicTests: XCTestCase {

    func testTappingANewRowExpandsIt() {
        XCTAssertEqual(LabelsLogic.nextExpanded(current: nil, tapped: .existing("l1")), .existing("l1"))
        XCTAssertEqual(LabelsLogic.nextExpanded(current: .existing("l1"), tapped: .existing("l2")), .existing("l2"))
    }

    func testTappingTheExpandedRowCollapsesIt() {
        XCTAssertNil(LabelsLogic.nextExpanded(current: .existing("l1"), tapped: .existing("l1")))
    }

    func testTappingTheToolbarPlusOpensTheDraftAndClosesAnOpenRow() {
        XCTAssertEqual(LabelsLogic.nextExpanded(current: .existing("l1"), tapped: .new), .new)
    }

    func testTappingTheToolbarPlusAgainClosesTheDraft() {
        XCTAssertNil(LabelsLogic.nextExpanded(current: .new, tapped: .new))
    }

    func testExpandingARowClosesAnOpenDraft() {
        XCTAssertEqual(LabelsLogic.nextExpanded(current: .new, tapped: .existing("l1")), .existing("l1"))
    }
}

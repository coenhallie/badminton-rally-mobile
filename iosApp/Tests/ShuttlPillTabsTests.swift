import XCTest
@testable import iosApp

/// How a pill row fits its labels: padding first, then one factor for the whole
/// row, never a break inside a word above the floor. Port of androidApp's
/// `PillLabelScaleTest`, case for case.
final class ShuttlPillTabsTests: XCTestCase {

    /// A word's width at a factor, scaled linearly.
    private func linear(_ width: CGFloat) -> (CGFloat) -> CGFloat {
        { scale in width * scale }
    }

    func testARowWhoseWordsFitKeepsItsSize() {
        XCTAssertEqual(pillLabelScale(limit: 120, measure: linear(120)), 1)
    }

    func testTheWidestWordSetsTheFactorForTheRow() {
        // "Analysis" at a large text size on a narrow phone: 198 of word in 171
        // of pill.
        let scale = pillLabelScale(limit: 171, measure: linear(198))
        XCTAssertLessThanOrEqual(198 * scale, 171)
        XCTAssertEqual(scale, 171 / 198, accuracy: 0.01)
    }

    /// Text measure need not fall in proportion to the factor. The first
    /// proportional guess leaves the word too wide; the fit measures again until
    /// it is not.
    func testAWidthThatDoesNotScaleLinearlyIsMeasuredAgainUntilItFits() {
        let nonLinear: (CGFloat) -> CGFloat = { scale in 198 * (0.4 + 0.6 * scale) }
        let scale = pillLabelScale(limit: 171, measure: nonLinear)
        XCTAssertLessThanOrEqual(nonLinear(scale), 171)
        // The proportional guess alone would still be too wide.
        XCTAssertGreaterThan(nonLinear(171 / 198), 171)
    }

    func testTheFactorStopsAtTheFloor() {
        XCTAssertEqual(pillLabelScale(limit: 100, measure: linear(400)), minPillLabelScale)
    }

    func testNothingToMeasureLeavesTheSizeAlone() {
        XCTAssertEqual(pillLabelScale(limit: 100, measure: { _ in 0 }), 1)
    }

    /// The ceiling: a secondary row's label size held to the primary's above it.
    func testASecondaryLabelIsHeldToThePrimaryLabelSize() {
        // 11pt at a large text size comes to 14.3pt; a primary shrunk to 12pt.
        let scale = pillLabelScale(limit: 12, measure: linear(14.3))
        XCTAssertLessThanOrEqual(14.3 * scale, 12)
    }

    func testAPillKeepsItsFullPaddingWhileTheWordFitsInsideIt() {
        XCTAssertEqual(pillPadding(pillWidth: 200, widestWord: 150, min: 6, max: 24), 24)
    }

    func testAPillGivesUpPaddingBeforeTheWordBreaks() {
        XCTAssertEqual(pillPadding(pillWidth: 200, widestWord: 180, min: 6, max: 24), 10)
    }

    func testAPillKeepsItsMinimumPaddingAndLeavesTheRestToTheScale() {
        XCTAssertEqual(pillPadding(pillWidth: 200, widestWord: 199, min: 6, max: 24), 6)
    }
}

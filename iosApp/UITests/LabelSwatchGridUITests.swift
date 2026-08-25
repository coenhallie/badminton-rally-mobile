import XCTest

/// Covers the swatch grid's hit testing, which no unit test can reach.
///
/// The bug this was written for: the ten swatches are `Button`s inside a `List`
/// row, and with the default `.automatic` button style SwiftUI activates *every*
/// button in a cell on a single tap. The taps ran in palette order, so whichever
/// swatch you touched, the last one written won and the label came out slate.
///
/// The draft row is used rather than an existing label deliberately: it holds
/// its colour in local state until the name commits, so the test never writes to
/// the account's real labels.
final class LabelSwatchGridUITests: XCTestCase {

    override func setUp() {
        continueAfterFailure = false
    }

    func testTappingASwatchSelectsThatSwatchAndNoOther() throws {
        let app = XCUIApplication()
        app.launch()

        try openLabelsScreen(app)

        app.buttons["Add label"].firstMatch.tap()

        let blue = app.buttons["Blue"].firstMatch
        XCTAssertTrue(blue.waitForExistence(timeout: 5), "swatch grid did not appear")

        blue.tap()

        // Reporting which swatch won turns "it did not select" into evidence
        // about where the tap actually went.
        XCTAssertEqual(selectedKeys(app), ["Blue"], "tapped Blue")
    }

    func testEachSwatchInTurnSelectsItself() throws {
        let app = XCUIApplication()
        app.launch()

        try openLabelsScreen(app)
        app.buttons["Add label"].firstMatch.tap()

        XCTAssertTrue(
            app.buttons["Green"].firstMatch.waitForExistence(timeout: 5),
            "swatch grid did not appear"
        )

        // Every swatch, not just one: a tap routed to the whole cell can happen
        // to land on the right colour for exactly one position in the palette.
        for key in Self.palette {
            app.buttons[key].firstMatch.tap()
            XCTAssertEqual(selectedKeys(app), [key], "tapped \(key)")
        }
    }

    private static let palette = ["Green", "Teal", "Blue", "Indigo", "Purple",
                                  "Pink", "Red", "Orange", "Amber", "Slate"]

    /// Every swatch currently carrying the selected trait. Exactly one should.
    private func selectedKeys(_ app: XCUIApplication) -> [String] {
        Self.palette.filter { app.buttons[$0].firstMatch.isSelected }
    }

    /// Signing in needs real credentials, so a signed-out simulator skips rather
    /// than reporting a failure that says nothing about the swatch grid.
    private func openLabelsScreen(_ app: XCUIApplication) throws {
        let menu = app.buttons["Menu"].firstMatch
        guard menu.waitForExistence(timeout: 15) else {
            throw XCTSkip("not signed in on this simulator - cannot reach the Labels screen")
        }
        menu.tap()
        app.buttons["Labels"].firstMatch.tap()
        XCTAssertTrue(
            app.navigationBars["Labels"].waitForExistence(timeout: 10),
            "Labels screen did not appear"
        )
    }
}

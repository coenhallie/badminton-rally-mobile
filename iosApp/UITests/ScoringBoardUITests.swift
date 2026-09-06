import XCTest

/// Covers the board's hit testing, which no unit test can reach.
///
/// The check this exists for is the one this codebase has already been bitten by
/// once, in `LabelSwatchGridUITests`: a tap meant for one control also firing the
/// one underneath it. Here that would mean tapping a label chip and scoring a
/// rally the coach did not play, which is the single worst thing this screen
/// could do - it corrupts the record silently and the coach finds out afterwards.
final class ScoringBoardUITests: XCTestCase {

    private let matchName = "UI test match"

    override func setUp() {
        continueAfterFailure = false
    }

    func testTappingALabelChipDoesNotAlsoScoreARally() throws {
        let app = XCUIApplication()
        app.launch()

        try createMatch(app)

        // One rally to the side on the left, which puts the tag row in reach.
        let board = app.otherElements.firstMatch
        scoreLeftHalf(app)
        XCTAssertTrue(
            app.staticTexts["Point 1: 1-0"].waitForExistence(timeout: 5),
            "the first tap did not score"
        )
        _ = board

        // The tap that matters. A chip is a sibling of the scoring halves rather
        // than a child, so this should be impossible - which is exactly the kind
        // of thing worth holding still with a test.
        let chip = app.buttons["Good shot"].firstMatch
        XCTAssertTrue(chip.waitForExistence(timeout: 5), "the tag row did not appear")
        chip.tap()

        XCTAssertTrue(
            app.staticTexts["Point 1: 1-0"].exists,
            "tapping a label chip also scored a rally"
        )

        try deleteMatch(app)
    }

    /// Creates a singles match from the real form, the way a coach reaches the
    /// board. Deleted again at the end of the test so the account keeps nothing.
    private func createMatch(_ app: XCUIApplication) throws {
        // Signing in needs real credentials, so a signed-out simulator skips
        // rather than reporting a failure that says nothing about the board.
        // The same guard LabelSwatchGridUITests uses, and for the same reason.
        //
        // Gated on the drawer control rather than on the pill this test goes on
        // to tap: those are both on Home, so either would prove the app is
        // signed in, but skipping on the pill's own absence would turn renaming
        // or losing it into a silent skip instead of the failure it should be.
        guard app.buttons["Menu"].firstMatch.waitForExistence(timeout: 15) else {
            throw XCTSkip("not signed in on this simulator - cannot reach the board")
        }
        // "Add" was the list's own toolbar icon; Home replaced it with the
        // "Add new match" pill, which opens the same three-row sheet.
        app.buttons["Add new match"].firstMatch.tap()
        let newMatch = app.buttons["New match"].firstMatch
        XCTAssertTrue(newMatch.waitForExistence(timeout: 5), "the add sheet did not open")
        newMatch.tap()

        let title = app.textFields["Match name"].firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 5), "the new match form did not open")
        title.tap()
        title.typeText(matchName)

        app.textFields["Home player"].firstMatch.tap()
        app.textFields["Home player"].firstMatch.typeText("Coen")
        app.textFields["Away player"].firstMatch.tap()
        app.textFields["Away player"].firstMatch.typeText("Marco")

        app.buttons["Create"].firstMatch.tap()
        XCTAssertTrue(
            app.staticTexts["Tap a side to score"].waitForExistence(timeout: 10),
            "creating a match did not land on the board"
        )
    }

    /// The left half of the board, tapped by coordinate: the zone is a full-bleed
    /// region rather than a control, which is the whole point of it.
    private func scoreLeftHalf(_ app: XCUIApplication) {
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0.5)).tap()
    }

    /// Leaves the account as the test found it. The board has no navigation bar,
    /// so the way off it is its own back control - the same one a coach uses.
    ///
    /// Every row with this name, not just the first: an earlier run that failed
    /// before its own cleanup leaves one behind, and a test that then cannot get
    /// back to a clean device stays red for a reason that is not about the board.
    private func deleteMatch(_ app: XCUIApplication) throws {
        app.buttons["Back"].firstMatch.tap()
        // Back lands on Home, not the list: the list is now behind the
        // drawer, so it takes the hamburger to reach the row this test needs.
        app.buttons["Open matches"].firstMatch.tap()
        // Scrolled to, not assumed on screen. The drawer lists videos on this
        // phone above matches, and a SwiftUI List is lazy, so a row below the
        // fold is not in the accessibility tree at all. This test used to pass
        // only because the device happened to hold one local video; it fails on
        // any account with enough of them, which is a property of the account
        // rather than of the board this test is about.
        let row = app.staticTexts[matchName].firstMatch
        var scrolls = 0
        while !row.exists && scrolls < 10 {
            app.swipeUp()
            scrolls += 1
        }
        XCTAssertTrue(row.waitForExistence(timeout: 5), "the match did not come back to the list")

        while app.staticTexts[matchName].firstMatch.exists {
            app.staticTexts[matchName].firstMatch.swipeLeft()
            let delete = app.buttons["Delete"].firstMatch
            XCTAssertTrue(delete.waitForExistence(timeout: 3), "no delete action on the row")
            delete.tap()
            // The confirm carries the same label as the swipe action, so it is
            // looked for inside the dialog rather than anywhere on screen.
            let confirm = app.sheets.buttons["Delete"].firstMatch
            XCTAssertTrue(confirm.waitForExistence(timeout: 5), "the delete confirmation did not open")
            confirm.tap()
            _ = app.staticTexts[matchName].firstMatch.waitForNonExistence(timeout: 3)
        }
    }
}

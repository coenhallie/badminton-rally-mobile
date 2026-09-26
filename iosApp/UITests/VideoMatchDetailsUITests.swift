import XCTest

/// A match that was only ever filmed can have its details edited: the menu a
/// scored match has, the same editor, plus the description the video carries.
///
/// Reads "cloudia", the video-born match on the verification account; skips
/// anywhere it is absent rather than failing for a reason that is not about the
/// page. Nothing is saved: the save needs the 20260922000000 migration on the
/// project, and a refused save is the unit tests' business.
final class VideoMatchDetailsUITests: XCTestCase {

    override func setUp() {
        continueAfterFailure = false
    }

    func testAVideoBornMatchOffersTheDetailsEditorWithADescription() throws {
        let app = XCUIApplication()
        app.launch()

        // Gated on the tab bar, which only exists behind sign-in, rather than on
        // a control this test otherwise needs - the Matches tab's list is on
        // screen at launch, with nothing to open first.
        guard app.tabBars.buttons["Settings"].firstMatch.waitForExistence(timeout: 15) else {
            throw XCTSkip("not signed in on this simulator - cannot reach a match")
        }
        // Scrolled to, not assumed on screen: the list carries the videos on this
        // phone above the matches, and a SwiftUI List is lazy, so a row below the
        // fold is not in the accessibility tree at all - see ScoringBoardUITests.
        let row = app.staticTexts["cloudia"].firstMatch
        _ = row.waitForExistence(timeout: 3)
        var scrolls = 0
        while !row.isHittable && scrolls < 10 {
            app.swipeUp()
            scrolls += 1
        }
        guard row.isHittable else {
            throw XCTSkip("the cloudia match is not on this account")
        }
        row.tap()

        // The menu a video-born match never had.
        let options = app.buttons["Match options"].firstMatch
        XCTAssertTrue(options.waitForExistence(timeout: 8), "a filmed match of the coach's own has no options menu")
        // Held for the screenshot loop that runs beside this test; see the
        // emulator-sim memory for why there is no other way in.
        Thread.sleep(forTimeInterval: 6)
        options.tap()
        let edit = app.buttons["Edit details"].firstMatch
        XCTAssertTrue(edit.waitForExistence(timeout: 5), "the menu does not offer the details editor")
        // Export is a score log's: there are no points to export here.
        XCTAssertFalse(app.buttons["Export as text"].firstMatch.exists, "a filmed match offers a points export")
        Thread.sleep(forTimeInterval: 6)
        edit.tap()

        let name = app.textFields["Match name"].firstMatch
        XCTAssertTrue(name.waitForExistence(timeout: 5), "the details editor did not open")
        XCTAssertEqual(name.value as? String, "cloudia", "the editor did not open on the video's name")
        XCTAssertTrue(app.staticTexts["Description"].firstMatch.exists, "the video's description is not editable")
        XCTAssertTrue(app.buttons["Doubles"].firstMatch.exists, "the format picker is missing")
        XCTAssertTrue(app.textFields["Home player"].firstMatch.exists, "the player fields are missing")
        Thread.sleep(forTimeInterval: 8)

        app.buttons["Cancel"].firstMatch.tap()
        XCTAssertTrue(options.waitForExistence(timeout: 5), "cancel did not return to the match")
    }
}

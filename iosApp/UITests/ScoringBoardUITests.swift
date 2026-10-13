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

        // At 0-0 a chip logs a moment and must not score a rally.
        app.buttons["Good shot"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Moment added: Good shot"].waitForExistence(timeout: 5), "a chip at 0-0 did not log a moment")
        XCTAssertTrue(app.staticTexts["1 moment logged"].exists, "a chip at 0-0 also scored a rally")

        // The serve pill sits inside the half that scores on a tap, so handing the
        // opening serve across must not also be a rally. Held still here for the
        // same reason the chip is below. After a moment on purpose: a moment is
        // not a rally, so the switch must still take, not just still be offered.
        // Only the serving side's pill is a button, so the one found is on the
        // side that serves.
        let serve = app.buttons["SERVE R"].firstMatch
        XCTAssertTrue(serve.waitForExistence(timeout: 5), "the serve pill cannot be switched before the first point")
        let middle = app.frame.midX
        let servedFromLeft = serve.frame.midX < middle
        serve.tap()
        XCTAssertTrue(app.staticTexts["1 moment logged"].exists, "switching the first server also scored a rally")
        let switched = app.buttons["SERVE R"].firstMatch
        XCTAssertTrue(switched.waitForExistence(timeout: 5), "the serve pill went away after a switch")
        XCTAssertNotEqual(switched.frame.midX < middle, servedFromLeft, "SERVE did not hand the serve across after a moment")

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

    func testAtNilAllNoteOpensTheMomentComposer() throws {
        let app = XCUIApplication()
        app.launch()

        try createMatch(app)

        // With no rally to put a note on, Note writes a moment instead.
        app.buttons["Note"].firstMatch.tap()
        let field = app.textFields["Note on this moment"].firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5), "Note at 0-0 did not open the moment composer")
        field.typeText("short serve")
        app.buttons["Note"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Moment added"].waitForExistence(timeout: 5), "Note did not save the moment")
        XCTAssertTrue(app.staticTexts["1 moment logged"].exists, "the saved moment is not counted")

        try deleteMatch(app)
    }

    func testUndoTakesBackATaggedRallyAndRedoPutsItBack() throws {
        let app = XCUIApplication()
        app.launch()

        try createMatch(app)

        scoreLeftHalf(app)
        XCTAssertTrue(app.staticTexts["Point 1: 1-0"].waitForExistence(timeout: 5), "the first tap did not score")
        scoreRightHalf(app)
        XCTAssertTrue(app.staticTexts["Point 2: 1-1"].waitForExistence(timeout: 5), "the second tap did not score")
        // Tagged, because a tagged rally is the case the old undo got wrong: it
        // took the tag first and left the score where it was.
        app.buttons["Good shot"].firstMatch.tap()

        // In the menu since the score editor took its place on the bar.
        app.buttons["Match options"].firstMatch.tap()
        let undo = app.buttons["Undo last rally"].firstMatch
        XCTAssertTrue(undo.waitForExistence(timeout: 5), "the board's menu does not offer undo")
        undo.tap()
        XCTAssertTrue(
            app.staticTexts["Point to Away undone"].waitForExistence(timeout: 5),
            "undo did not say what it took back"
        )
        XCTAssertTrue(app.staticTexts["Point 1: 1-0"].exists, "one undo did not take back the tagged rally")
        // Held for the screenshot loop that runs beside this test.
        Thread.sleep(forTimeInterval: 2)

        app.buttons["Redo"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Point 2: 1-1"].waitForExistence(timeout: 5), "redo did not put the rally back")
        XCTAssertFalse(app.buttons["Redo"].exists, "redo was still offered after it was used")

        try deleteMatch(app)
    }

    func testTheScoreEditorSetsBothScoresInOneSave() throws {
        let app = XCUIApplication()
        app.launch()

        try createMatch(app)
        scoreLeftHalf(app)
        XCTAssertTrue(app.staticTexts["Point 1: 1-0"].waitForExistence(timeout: 5), "the first tap did not score")

        app.buttons["Edit score"].firstMatch.tap()
        let plus = app.buttons["Add a point for Away"].firstMatch
        XCTAssertTrue(plus.waitForExistence(timeout: 5), "the score editor did not open")
        // A stepper for the one-point fix, and typing for the jump.
        plus.tap()
        plus.tap()
        let home = app.textFields["Home score"].firstMatch
        home.tap()
        home.typeText("5")
        XCTAssertTrue(
            app.staticTexts["Adds 4 rallies for Home and 2 for Away."].waitForExistence(timeout: 5),
            "the editor did not say what saving would do"
        )
        // Held for the screenshot loop that runs beside this test.
        Thread.sleep(forTimeInterval: 3)
        app.buttons["Save"].firstMatch.tap()

        XCTAssertTrue(
            app.staticTexts["Point 7: 5-2"].waitForExistence(timeout: 5),
            "the board did not take the edited score"
        )
        Thread.sleep(forTimeInterval: 2)

        try deleteMatch(app)
    }

    func testDetailsEditedFromTheBoardNameTheSides() throws {
        let app = XCUIApplication()
        app.launch()

        try createMatch(app)
        XCTAssertTrue(app.staticTexts["Home"].exists, "an unnamed side is not called Home")

        // The new match form asks for none of this; the board's menu is one of the
        // two places it gets filled in.
        app.buttons["Match options"].firstMatch.tap()
        let edit = app.buttons["Edit details"].firstMatch
        XCTAssertTrue(edit.waitForExistence(timeout: 5), "the board's menu does not offer the details editor")
        edit.tap()

        let doubles = app.buttons["Doubles"].firstMatch
        XCTAssertTrue(doubles.waitForExistence(timeout: 5), "the details editor did not open")
        doubles.tap()

        for (field, name) in [("Home player 1", "Coen"), ("Home player 2", "Ana"),
                              ("Away player 1", "Marco"), ("Away player 2", "Li")] {
            let input = app.textFields[field].firstMatch
            XCTAssertTrue(input.waitForExistence(timeout: 5), "doubles did not show \(field)")
            input.tap()
            input.typeText(name)
        }
        app.buttons["Save"].firstMatch.tap()

        XCTAssertTrue(
            app.staticTexts["Coen / Ana"].waitForExistence(timeout: 5),
            "the board did not take the home pair's names"
        )
        XCTAssertTrue(app.staticTexts["Marco / Li"].exists, "the board did not take the away pair's names")
        // Holds the named board on screen for the screenshot loop that runs beside
        // this test; see the emulator-sim memory for why there is no other way in.
        Thread.sleep(forTimeInterval: 3)

        try deleteMatch(app)
    }

    /// Creates a singles match from the real form, the way a coach reaches the
    /// board. Deleted again at the end of the test so the account keeps nothing.
    private func createMatch(_ app: XCUIApplication) throws {
        // Signing in needs real credentials, so a signed-out simulator skips
        // rather than reporting a failure that says nothing about the board.
        // The same guard LabelSwatchGridUITests uses, and for the same reason.
        //
        // Gated on the list's Settings gear rather than on the Add button this
        // test goes on to tap: the gear only exists behind sign-in, so it
        // proves the app is signed in without also being the control a rename
        // or a loss of "Add match" would need to fail on instead of silently
        // skip.
        guard app.buttons["Settings"].firstMatch.waitForExistence(timeout: 15) else {
            throw XCTSkip("not signed in on this simulator - cannot reach the board")
        }
        // "Add" was the list's own toolbar icon, then Home's "Add new match"
        // pill; the match list's own round "Add match" button opens the same
        // three-row sheet now.
        app.buttons["Add match"].firstMatch.tap()
        let newMatch = app.buttons["New match"].firstMatch
        XCTAssertTrue(newMatch.waitForExistence(timeout: 5), "the add sheet did not open")
        newMatch.tap()

        // The form asks where the phone is, once, to put a town in the default
        // name. Answered here so the alert cannot sit over the fields below.
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allow = springboard.buttons["Allow While Using App"].firstMatch
        if allow.waitForExistence(timeout: 3) { allow.tap() }

        let title = app.textFields["Match name"].firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 5), "the new match form did not open")
        // The name arrives filled in, so it is cleared before the test's own is typed:
        // deleting the match afterwards finds it by this exact name.
        title.tap()
        let prefilled = (title.value as? String) ?? ""
        title.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: prefilled.count))
        title.typeText(matchName)

        app.buttons["Create"].firstMatch.tap()
        // The 0-0 caption names labels when the account has board labels, as the
        // simulator account does, and SERVE when it has none. Either is the board.
        XCTAssertTrue(
            app.staticTexts["Tap a side to score, or a label to log a moment"].waitForExistence(timeout: 10)
                || app.staticTexts["Tap a side to score, or SERVE to switch server"].exists,
            "creating a match did not land on the board"
        )
    }

    /// The left half of the board, tapped by coordinate: the zone is a full-bleed
    /// region rather than a control, which is the whole point of it.
    private func scoreLeftHalf(_ app: XCUIApplication) {
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0.5)).tap()
    }

    private func scoreRightHalf(_ app: XCUIApplication) {
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.75, dy: 0.5)).tap()
    }

    /// Swipes the list until [element] is on screen and touchable, or gives up.
    ///
    /// `exists` is not the same question: it is true for a row the list has
    /// built and scrolled away, and every gesture on such a row fails.
    private func scrollIntoView(_ app: XCUIApplication, _ element: XCUIElement) -> Bool {
        var scrolls = 0
        while !element.isHittable && scrolls < 10 {
            app.swipeUp()
            scrolls += 1
        }
        return element.isHittable
    }

    /// Leaves the account as the test found it. The board has no navigation bar,
    /// so the way off it is its own back control - the same one a coach uses.
    ///
    /// Every row with this name, not just the first: an earlier run that failed
    /// before its own cleanup leaves one behind, and a test that then cannot get
    /// back to a clean device stays red for a reason that is not about the board.
    private func deleteMatch(_ app: XCUIApplication) throws {
        app.buttons["Back"].firstMatch.tap()
        // Back lands on the match list the board was created from, already on
        // screen - no hamburger, no drawer to open first.
        //
        // Scrolled to, not assumed on screen. The list carries videos on this
        // phone above matches, and a SwiftUI List is lazy, so a row below the
        // fold is not in the accessibility tree at all. This test used to pass
        // only because the device happened to hold one local video; it fails on
        // any account with enough of them, which is a property of the account
        // rather than of the board this test is about.
        let row = app.staticTexts[matchName].firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5) || scrollIntoView(app, row),
                      "the match did not come back to the list")

        while app.staticTexts[matchName].firstMatch.exists {
            let row = app.staticTexts[matchName].firstMatch
            // Scrolled until it can be TOUCHED, not until it exists. A SwiftUI
            // List keeps a row in the accessibility tree for a while after it
            // leaves the screen, and a swipe on one of those fails with "visible
            // frame is empty" - which is what made this cleanup fail on a device
            // holding enough rows to push this one under the fold.
            XCTAssertTrue(scrollIntoView(app, row), "the match row never came on screen")
            row.swipeLeft()
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

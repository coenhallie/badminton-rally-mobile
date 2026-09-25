import XCTest

/// The replay controls on a real player: the gear where the speed chip used to be,
/// the speed in the transport row, one-tap tagging, and fullscreen with the
/// floating label window.
///
/// Drives the local video player, which needs no network and shares every piece
/// under test with the rally replay page (`ShuttlPlayer`).
final class ReplayPlayerUITests: XCTestCase {

    override func setUp() {
        continueAfterFailure = false
    }

    func testTaggingFromTheRowAndFromTheFullscreenWindow() throws {
        let app = XCUIApplication()
        app.launch()

        guard app.buttons["Menu"].firstMatch.waitForExistence(timeout: 15) else {
            throw XCTSkip("not signed in on this simulator - cannot reach a player")
        }
        app.buttons["Open matches"].firstMatch.tap()
        let video = app.staticTexts["Chrome fixture"].firstMatch
        guard video.waitForExistence(timeout: 5) else {
            throw XCTSkip("the fixture video is not on this simulator")
        }
        // The row exists before the drawer has finished sliding in, and off screen
        // it cannot be tapped.
        let onScreen = XCTNSPredicateExpectation(predicate: NSPredicate(format: "isHittable == true"), object: video)
        XCTAssertEqual(XCTWaiter().wait(for: [onScreen], timeout: 5), .completed, "the drawer did not open")
        video.tap()

        // (b): a gear, not "1x", on the card - and the speed in the transport row.
        XCTAssertTrue(app.buttons["Playback settings"].firstMatch.waitForExistence(timeout: 10), "no settings gear on the video card")
        let speed = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Speed '")).firstMatch
        XCTAssertTrue(speed.exists, "the speed is not read out in the transport row")

        // (a), not fullscreen: a chip under the transport tags in one tap.
        let chip = app.buttons["Good shot"].firstMatch
        guard chip.waitForExistence(timeout: 5) else {
            throw XCTSkip("this account has no 'Good shot' label scoped to clips")
        }
        chip.tap()
        let tagged = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Tagged Good shot at'")).firstMatch
        XCTAssertTrue(tagged.waitForExistence(timeout: 3), "tapping a label did not tag the moment")
        Thread.sleep(forTimeInterval: 2)

        // (a), fullscreen: the same chips in a window over the video.
        app.buttons["Fullscreen"].firstMatch.tap()
        let window = app.otherElements["Labels window. Drag to move."].firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 8), "fullscreen has no labels window")
        XCTAssertTrue(app.buttons["Exit fullscreen"].firstMatch.exists)
        Thread.sleep(forTimeInterval: 3)

        // Dragged by its title bar, towards the middle of the screen.
        let start = window.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.5))
        let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.4))
        start.press(forDuration: 0.2, thenDragTo: end)
        Thread.sleep(forTimeInterval: 3)

        app.buttons["Hide labels"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Show labels"].firstMatch.waitForExistence(timeout: 3), "the window did not fold")
        Thread.sleep(forTimeInterval: 2)

        app.buttons["Exit fullscreen"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Fullscreen"].firstMatch.waitForExistence(timeout: 8), "did not come back from fullscreen")

        // Leave the fixture as it was found.
        let delete = app.buttons["Delete note"].firstMatch
        if delete.waitForExistence(timeout: 3) {
            delete.tap()
            app.buttons["Delete"].firstMatch.tap()
        }
    }
}

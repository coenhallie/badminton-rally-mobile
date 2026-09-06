import XCTest
import CoreGraphics
@testable import iosApp

/// The drawer's arithmetic, tested without a gesture.
///
/// SwiftUI has no drawer primitive so this is a hand built overlay, and the
/// parts that can be wrong in a way a screenshot will not show - a threshold, a
/// clamp, an opacity ramp - are pure functions here rather than inline in the
/// view. Same reasoning as FrameStepMath and CourtTapMath.
final class DrawerDragMathTests: XCTestCase {

    func testWidthIsCappedOnLargeScreensAndProportionalOnSmall() {
        // 393pt is a standard modern iPhone's width, and 86% of that (337.98)
        // already exceeds the 330 cap, so this size takes the cap.
        XCTAssertEqual(DrawerDragMath.width(forScreenWidth: 393), 330, accuracy: 0.01)
        // 375pt (iPhone SE/mini) is under the crossover (330 / 0.86 = 383.72),
        // so it genuinely exercises the proportional branch.
        XCTAssertEqual(DrawerDragMath.width(forScreenWidth: 375), 375 * 0.86, accuracy: 0.01)
        // A large screen takes the cap, so the drawer never becomes a full page.
        XCTAssertEqual(DrawerDragMath.width(forScreenWidth: 1024), 330, accuracy: 0.01)
    }

    func testOpensOnADeliberateDrag() {
        XCTAssertTrue(DrawerDragMath.shouldOpen(translation: 71, velocity: 0))
        XCTAssertFalse(DrawerDragMath.shouldOpen(translation: 69, velocity: 0))
        // Pins the inclusive boundary itself, so a >= to > change on
        // openThreshold is caught even though it would pass 69/71.
        XCTAssertTrue(DrawerDragMath.shouldOpen(translation: 70, velocity: 0))
    }

    func testOpensOnAFastFlickThatDidNotTravelFar() {
        // A flick is a real gesture: short travel, high speed. Without this the
        // drawer would refuse to open for anyone who swipes quickly.
        XCTAssertTrue(DrawerDragMath.shouldOpen(translation: 20, velocity: 900))
        XCTAssertFalse(DrawerDragMath.shouldOpen(translation: 20, velocity: 100))
        // Pins the inclusive boundary itself, so a >= to > change on
        // velocityThreshold is caught even though it would pass 100/900.
        XCTAssertTrue(DrawerDragMath.shouldOpen(translation: 20, velocity: 300))
    }

    func testABackwardDragNeverOpens() {
        XCTAssertFalse(DrawerDragMath.shouldOpen(translation: -200, velocity: -900))
        // A backward translation must not open even when velocity alone would
        // clear the flick threshold - the direction guard has to run first.
        XCTAssertFalse(DrawerDragMath.shouldOpen(translation: -200, velocity: 900))
    }

    func testClosesOnADeliberateDrag() {
        XCTAssertTrue(DrawerDragMath.shouldClose(translation: -71, velocity: 0))
        XCTAssertFalse(DrawerDragMath.shouldClose(translation: -69, velocity: 0))
        // Pins the inclusive boundary itself, mirroring shouldOpen: exactly
        // -70 must close just as exactly +70 opens.
        XCTAssertTrue(DrawerDragMath.shouldClose(translation: -70, velocity: 0))
    }

    func testClosesOnAFastFlickThatDidNotTravelFar() {
        // A flick is a real gesture: short travel, high speed, in the closing
        // direction. Without this the drawer would refuse to close for anyone
        // who swipes quickly.
        XCTAssertTrue(DrawerDragMath.shouldClose(translation: -20, velocity: -900))
        XCTAssertFalse(DrawerDragMath.shouldClose(translation: -20, velocity: -100))
        // Pins the inclusive boundary itself: exactly -300 must close just as
        // exactly +300 opens.
        XCTAssertTrue(DrawerDragMath.shouldClose(translation: -20, velocity: -300))
    }

    func testAForwardDragNeverCloses() {
        XCTAssertFalse(DrawerDragMath.shouldClose(translation: 200, velocity: 900))
        // A forward translation must not close even when velocity alone would
        // clear the flick threshold - the direction guard has to run first.
        XCTAssertFalse(DrawerDragMath.shouldClose(translation: 200, velocity: -900))
    }

    func testOffsetIsClampedToTheDrawerWidth() {
        let w: CGFloat = 330
        // Closed, mid drag: sits between fully hidden and fully open.
        XCTAssertEqual(DrawerDragMath.offset(translation: 100, width: w, isOpen: false), -230, accuracy: 0.01)
        // Over dragging past open does not push the drawer past its own edge.
        XCTAssertEqual(DrawerDragMath.offset(translation: 999, width: w, isOpen: false), 0, accuracy: 0.01)
        // Dragging backwards from closed does not pull it further off screen.
        XCTAssertEqual(DrawerDragMath.offset(translation: -999, width: w, isOpen: false), -w, accuracy: 0.01)
        // Open, dragged back toward closed: the panel follows the finger.
        XCTAssertEqual(DrawerDragMath.offset(translation: -100, width: w, isOpen: true), -100, accuracy: 0.01)
    }

    func testScrimIsInvisibleWhenClosedAndFullWhenOpen() {
        let w: CGFloat = 330
        XCTAssertEqual(DrawerDragMath.scrimOpacity(offset: -w, width: w), 0, accuracy: 0.001)
        XCTAssertEqual(DrawerDragMath.scrimOpacity(offset: 0, width: w), 1, accuracy: 0.001)
        XCTAssertEqual(DrawerDragMath.scrimOpacity(offset: -w / 2, width: w), 0.5, accuracy: 0.001)
    }

    func testScrimOpacityNeverEscapesZeroToOne() {
        // Guards the overlay against a stale or overshooting offset making the
        // scrim opaque over a closed drawer, which would block the whole screen.
        for o in [-9999, -331, 0, 1, 9999].map(CGFloat.init) {
            let v = DrawerDragMath.scrimOpacity(offset: o, width: 330)
            XCTAssertTrue((0...1).contains(v), "opacity \(v) escaped 0...1 at offset \(o)")
        }
    }

    func testDegenerateWidthDoesNotDivideByZero() {
        XCTAssertEqual(DrawerDragMath.scrimOpacity(offset: 0, width: 0), 0, accuracy: 0.001)
        // A negative width is also degenerate. Without the guard, 0 / -5 is
        // negative zero, and 1 - (-0) is 1, so this would read as a fully
        // opaque scrim over a drawer with no width at all.
        XCTAssertEqual(DrawerDragMath.scrimOpacity(offset: 0, width: -5), 0, accuracy: 0.001)
    }

    func testClosingTranslationPassesThroughNegativeAndClampsPositive() {
        // A closing drag reports a negative translation, which must pass
        // through unchanged so the panel follows the finger.
        XCTAssertEqual(DrawerDragMath.closingTranslation(-42), -42, accuracy: 0.01)
        // A forward wiggle mid-close-drag must not push the state positive.
        XCTAssertEqual(DrawerDragMath.closingTranslation(42), 0, accuracy: 0.01)
        XCTAssertEqual(DrawerDragMath.closingTranslation(0), 0, accuracy: 0.01)
    }
}

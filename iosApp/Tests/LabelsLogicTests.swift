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

final class CommitGuardTests: XCTestCase {

    /// `EditorFields` commits on both Done (`.onSubmit`) and the focus loss
    /// that follows the keyboard dismissing, so a single Done press reaches
    /// `begin` twice with the same text. The first must dispatch.
    func testFirstCommitFires() {
        var guardValue = CommitGuard()
        XCTAssertTrue(guardValue.begin("Smash"))
    }

    /// The second call from that same Done press must not re-dispatch: this
    /// is exactly the bug where a label is created once but a second,
    /// phantom create fires immediately after, which the repository's
    /// duplicate-name check correctly rejects - so the label is created and
    /// an error is shown anyway.
    func testImmediateRepeatWithSameTextDoesNotFire() {
        var guardValue = CommitGuard()
        XCTAssertTrue(guardValue.begin("Smash"))
        XCTAssertFalse(guardValue.begin("Smash"))
    }

    /// A genuine rejection (a real duplicate name) must roll the guard back,
    /// so retyping the identical text and resubmitting after resolving the
    /// collision still dispatches. This is the case most likely to regress
    /// silently: a guard keyed only on "did the text change" would treat the
    /// retry as an indistinguishable repeat and swallow it.
    func testSameTextAfterAFailureFiresAgain() {
        var guardValue = CommitGuard()
        let previous = guardValue.lastCommitted
        XCTAssertTrue(guardValue.begin("Smash"))
        guardValue.failed(previous: previous)
        XCTAssertTrue(guardValue.begin("Smash"))
    }

    /// Blank text never dispatches, committed or not.
    func testBlankTextNeverFires() {
        var guardValue = CommitGuard()
        XCTAssertFalse(guardValue.begin(""))
    }

    /// A rename's guard seeds from the label's current name (there is
    /// nothing to "commit" about text that already matches it), unlike a
    /// create's guard, which seeds empty.
    func testSeededGuardDoesNotFireForTheSeedValue() {
        var guardValue = CommitGuard(lastCommitted: "Good shot")
        XCTAssertFalse(guardValue.begin("Good shot"))
        XCTAssertTrue(guardValue.begin("Great shot"))
    }
}

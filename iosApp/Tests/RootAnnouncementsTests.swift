import Shared
import XCTest
@testable import iosApp

/// The root announcements' rules: the toast queue HomeView drives, the
/// finished-run toast's merge, the hand-over between the two list screens, and
/// which failed run the dialog is about and when it may show.
final class RootAnnouncementsTests: XCTestCase {

    private func message(_ text: String) -> ToastMessage {
        ToastMessage(text: text, actionLabel: nil, action: nil)
    }

    // MARK: - Queue

    func testAMessageWaitsUntilAListIsInFront() {
        var toasts = RootToasts()
        let failure = message("Couldn't import the video.")
        toasts.post(failure)

        toasts.deliver(listInFront: false)
        XCTAssertNil(toasts.showing, "a message showed under a pushed screen")

        toasts.deliver(listInFront: true)
        XCTAssertEqual(toasts.showing, failure)
    }

    func testMessagesShowOneAtATimeOldestFirst() {
        var toasts = RootToasts()
        let first = message("first"), second = message("second")
        toasts.post(first)
        toasts.post(second)
        toasts.deliver(listInFront: true)
        toasts.deliver(listInFront: true)
        XCTAssertEqual(toasts.showing, first)

        toasts.ended(first)
        toasts.deliver(listInFront: true)
        XCTAssertEqual(toasts.showing, second)
    }

    func testAnEndForAMessageNoLongerShowingChangesNothing() {
        var toasts = RootToasts()
        let old = message("old"), current = message("current")
        toasts.post(current)
        toasts.deliver(listInFront: true)

        toasts.ended(old)

        XCTAssertEqual(toasts.showing, current)
    }

    // MARK: - Finished runs

    func testASecondRunJoinsTheRunToastOnScreen() {
        var toasts = RootToasts()
        toasts.announce(runs: ["a"], message("1 match is analyzed"))
        toasts.deliver(listInFront: true)
        XCTAssertEqual(toasts.pendingRuns, ["a"])

        let both = message("2 matches are analyzed")
        toasts.announce(runs: ["a", "b"], both)

        XCTAssertEqual(toasts.showing, both, "the run toast was not replaced where it stood")
        XCTAssertTrue(toasts.waiting.isEmpty, "a second run toast was queued")
    }

    func testARunWaitsBehindAFailureRatherThanEvictingIt() {
        var toasts = RootToasts()
        let failure = message("Couldn't refresh matches.")
        toasts.post(failure)
        toasts.deliver(listInFront: true)

        let run = message("1 match is analyzed")
        toasts.announce(runs: ["a"], run)
        toasts.deliver(listInFront: true)
        XCTAssertEqual(toasts.showing, failure)
        XCTAssertEqual(toasts.waiting, [run])

        // A second run joins the waiting one in its place.
        let both = message("2 matches are analyzed")
        toasts.announce(runs: ["a", "b"], both)
        XCTAssertEqual(toasts.waiting, [both])
        XCTAssertEqual(toasts.pendingRuns, ["a", "b"])
    }

    func testOnceTheRunToastHasGoneTheNextStartsAfresh() {
        var toasts = RootToasts()
        let run = message("1 match is analyzed")
        toasts.announce(runs: ["a"], run)
        toasts.deliver(listInFront: true)

        toasts.ended(run)

        XCTAssertNil(toasts.pendingRuns)
    }

    // MARK: - Leaving the list screens

    func testLeavingTheListsEndsTheMessageOnScreenButNotTheQueue() {
        var toasts = RootToasts()
        let showing = message("Couldn't refresh matches."), next = message("Couldn't import the video.")
        toasts.post(showing)
        toasts.post(next)
        toasts.deliver(listInFront: true)

        toasts.listsLeft()

        XCTAssertNil(toasts.showing, "the message waited under the pushed screen for the coach's return")
        XCTAssertEqual(toasts.waiting, [next])
        // Back on a list, the next one shows.
        toasts.deliver(listInFront: true)
        XCTAssertEqual(toasts.showing, next)
    }

    func testARunToastLeftBehindStartsAfreshNextTime() {
        var toasts = RootToasts()
        toasts.announce(runs: ["a"], message("1 match is analyzed"))
        toasts.deliver(listInFront: true)

        toasts.listsLeft()

        XCTAssertNil(toasts.pendingRuns)
    }

    // MARK: - The failed-run dialog

    private func entry(
        _ id: String, stage: AnalyzeStage = .failed, seen: Bool = false,
        scoreLogId: String? = nil, failure: String = "The run failed"
    ) -> LocalVideoEntry {
        LocalVideoEntry(
            id: id, uri: "LocalVideos/\(id).mp4", displayName: "m.mp4",
            durationMs: 1000, sizeBytes: 2, addedAtEpochMs: 1,
            title: nil, description: nil, keypoints: nil,
            stage: stage, failedStep: nil, failureMessage: failure, resultSeen: seen,
            scoreLogId: scoreLogId,
            homePlayerId: nil, awayPlayerId: nil, homePlayers: [], awayPlayers: [], nearSide: nil
        )
    }

    func testTheDialogIsAboutAStandaloneFailureNotYetSeen() {
        let entries = [
            entry("analyzed", stage: .analyzed),
            entry("seen", seen: true),
            entry("a-match's", scoreLogId: "log-1"),
            entry("waiting"),
        ]
        XCTAssertEqual(FailedRunDialog.entry(held: nil, in: entries)?.id, "waiting")
        XCTAssertNil(FailedRunDialog.entry(held: nil, in: Array(entries.prefix(3))))
    }

    func testAHeldEntryIsReadAgainAsTheStoreHasItNow() {
        let held = entry("one", failure: "first reason")
        let now = [entry("other"), entry("one", failure: "second reason")]

        let picked = FailedRunDialog.entry(held: held, in: now)

        XCTAssertEqual(picked?.id, "one", "the held entry was swapped for another while it still waits")
        XCTAssertEqual(picked?.failureMessage, "second reason")
    }

    func testAHeldEntrySettledElsewhereGivesWayToTheNext() {
        let held = entry("one")
        let now = [entry("one", stage: .uploading), entry("two")]

        XCTAssertEqual(FailedRunDialog.entry(held: held, in: now)?.id, "two")
        XCTAssertNil(FailedRunDialog.entry(held: held, in: [entry("one", seen: true)]))
    }

    func testTheDialogShowsOnlyOnAListWithNothingOverIt() {
        XCTAssertTrue(FailedRunDialog.shows(hasEntry: true, listInFront: true, sheetOpen: false))
        XCTAssertFalse(FailedRunDialog.shows(hasEntry: false, listInFront: true, sheetOpen: false))
        // A pushed screen (the board, a match page with a sheet of its own up):
        // asking for an alert there took that screen's sheet down.
        XCTAssertFalse(FailedRunDialog.shows(hasEntry: true, listInFront: false, sheetOpen: false))
        XCTAssertFalse(FailedRunDialog.shows(hasEntry: true, listInFront: true, sheetOpen: true))
    }

    func testAFailureHeldUnderAPushedScreenNeverHoldsTheToastQueue() {
        // The review's wedge: a dialog counted as up while it could not be
        // presented kept every toast waiting. Held under a pushed screen it is
        // not up, and on the list it is presentable.
        var toasts = RootToasts()
        toasts.post(message("Couldn't import the video."))

        let onMatchPage = FailedRunDialog.shows(hasEntry: true, listInFront: false, sheetOpen: false)
        XCTAssertFalse(onMatchPage)

        // Back on Players the dialog shows first and the toast waits for it.
        let onPlayers = FailedRunDialog.shows(hasEntry: true, listInFront: true, sheetOpen: false)
        toasts.deliver(listInFront: !onPlayers)
        XCTAssertNil(toasts.showing)

        // Close acknowledges the entry; the toast follows.
        let afterClose = FailedRunDialog.shows(hasEntry: false, listInFront: true, sheetOpen: false)
        toasts.deliver(listInFront: !afterClose)
        XCTAssertNotNil(toasts.showing)
    }
}

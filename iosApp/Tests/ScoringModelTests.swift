import XCTest
import Shared
@testable import iosApp

/// The parity check. These are `ScoringViewModelTest`'s cases, name for name: if
/// the two lists diverge, the two surfaces have diverged.
@MainActor
final class ScoringModelTests: XCTestCase {

    // 2026-08-27T18:00:00Z. Fixed, because the store stamps created_at from it.
    private let t0 = KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: 1_787_853_600_000)

    private func label(_ id: String, _ name: String, _ color: String,
                       _ usage: LabelUsage = .both) -> AnnotationLabel {
        AnnotationLabel(id: id, name: name, colorKey: color, createdAt: t0, usage: usage.key)
    }

    private var goodShot: AnnotationLabel { label("l1", "Good shot", "green") }
    private var forcedError: AnnotationLabel { label("l2", "Forced error", "amber") }
    private var footwork: AnnotationLabel { label("l3", "Footwork", "teal", .clips) }

    /// The model plus the store and the match id behind it. The store is the
    /// local-only one, the same seam androidApp's tests use.
    private func fixture(
        rules: ScoringRules = ScoringRules.companion.BWF_21
    ) -> (ScoreLogsRepository, String, ScoringModel) {
        let repo = SwiftInteropKt.testScoreLogsRepository(now: t0, ownerId: "owner-1")
        let log = repo.create(
            title: "Thu League",
            rules: rules,
            setup: MatchSetup(firstServer: .home)
        )
        let model = ScoringModel(scoreLogs: repo, scoreboardLabels: [goodShot, forcedError], scoreLogId: log.id)
        return (repo, log.id, model)
    }

    func testDetailsEditedFromTheBoardAreStoredAndLeaveTheScoreAlone() {
        // The new match form asks for none of this, so the board is one of the two
        // places it gets filled in - mid-match, with points already on the log.
        let (repo, id, model) = fixture()
        model.score(.home)
        model.updateDetails(
            title: "Club final", format: .mixedDoubles,
            homePlayers: ["Coen", "Ana"], awayPlayers: ["Marco", " "]
        )
        let stored = repo.get(id: id)
        XCTAssertEqual(stored?.title, "Club final")
        XCTAssertEqual(stored?.format, .mixedDoubles)
        XCTAssertEqual(stored?.homePlayers, ["Coen", "Ana"])
        XCTAssertEqual(stored?.awayPlayers, ["Marco"])
        XCTAssertEqual(model.match?.currentGame.home, 1)
        XCTAssertEqual(model.log.map { $0.sideName(side: .home) }, "Coen / Ana")
    }

    func testTheOpeningServeCanBeSwitchedUntilTheFirstPoint() {
        // The form no longer asks who won the toss, so this is the only place the
        // coach can say it was the other side.
        let (_, _, model) = fixture()
        XCTAssertTrue(model.canSwitchFirstServer)

        model.switchFirstServer()
        XCTAssertEqual(model.match?.server, .away)

        model.score(.home)
        XCTAssertFalse(model.canSwitchFirstServer)
    }

    func testScoringAPointOpensTheTagRowForThatPoint() {
        // The whole interaction. The row is already on screen when the coach looks
        // down, so the second tap costs him nothing.
        let (_, _, model) = fixture()
        model.score(.home)
        XCTAssertEqual(model.pendingTagOrdinal, 0)
        XCTAssertEqual(model.match?.currentGame.home, 1)
        XCTAssertEqual(model.match?.currentGame.away, 0)
    }

    func testScoringTheNextPointMovesTheTagRowToIt() {
        let (_, _, model) = fixture()
        model.score(.home)
        model.toggleTag(ordinal: 0, label: goodShot)
        model.score(.away)
        XCTAssertEqual(model.pendingTagOrdinal, 1)
        // And the rally that was already tagged keeps its tag.
        XCTAssertEqual(model.match?.points[0].tags.map(\.labelName), ["Good shot"])
    }

    func testTaggingDoesNotCloseTheRow() {
        // A rally can be two things at once, and re-opening the row to say so would
        // cost the coach the next point.
        let (_, _, model) = fixture()
        model.score(.home)
        model.toggleTag(ordinal: 0, label: forcedError)
        XCTAssertEqual(model.pendingTagOrdinal, 0)
        model.toggleTag(ordinal: 0, label: goodShot)
        XCTAssertEqual(model.match?.points[0].tags.map(\.labelName), ["Forced error", "Good shot"])
    }

    func testTogglingTheSameLabelTwiceRemovesIt() {
        let (_, _, model) = fixture()
        model.score(.home)
        model.toggleTag(ordinal: 0, label: goodShot)
        model.toggleTag(ordinal: 0, label: goodShot)
        XCTAssertEqual(model.match?.points[0].tags.count, 0)
        // Undoing a mis-tap must not touch the score.
        XCTAssertEqual(model.match?.currentGame.home, 1)
    }

    func testATagSnapshotsTheLabelsNameAndColour() {
        // Not the label id. Renaming "Forced error" next month must not rewrite what
        // the coach recorded tonight.
        let (_, _, model) = fixture()
        model.score(.home)
        model.toggleTag(ordinal: 0, label: forcedError)
        let tag = model.match?.points[0].tags.first
        XCTAssertEqual(tag?.labelName, "Forced error")
        XCTAssertEqual(tag?.labelColor, "amber")
    }

    func testTheBoardOffersOnlyLabelsScopedToIt() {
        // Mirrors ScoringViewModelTest.the_board_offers_only_labels_scoped_to_it.
        // The model is handed the already-scoped set: filtering is the
        // repository's job and is asserted in commonTest against it.
        let repo = SwiftInteropKt.testScoreLogsRepository(now: t0, ownerId: "owner-1")
        let log = repo.create(
            title: "Thu League",
            rules: ScoringRules.companion.BWF_21,
            setup: MatchSetup(firstServer: .home)
        )
        let model = ScoringModel(
            scoreLogs: repo,
            scoreboardLabels: [goodShot, forcedError],
            scoreLogId: log.id
        )

        XCTAssertEqual(model.labels.map(\.id), ["l1", "l2"])
        XCTAssertFalse(model.labels.contains { $0.id == footwork.id })
    }

    func testHasAnyLabelsIsTrueEvenWhenNoneOfThemAreScopedToTheBoard() {
        // Mirrors ScoringViewModelTest.hasAnyLabels_is_true_even_when_none_of_them_are_scoped_to_the_board.
        // The account is not empty - it has one clips-only label. The board must
        // say "none of yours are on the board", not "you have not made any
        // labels", and those are different messages for different problems.
        //
        // A real repository double, the same seam MatchModelTests uses for its
        // own dependencies: only through the repository path does `labels` and
        // `scoreboardLabels` actually diverge, which is the whole point of this
        // test - a fixed array cannot exercise it.
        let repo = SwiftInteropKt.testScoreLogsRepository(now: t0, ownerId: "owner-1")
        let log = repo.create(
            title: "Thu League",
            rules: ScoringRules.companion.BWF_21,
            setup: MatchSetup(firstServer: .home)
        )
        let labels = AnnotationLabelsTestDoublesKt.testAnnotationLabelsRepository(labels: [footwork])
        let model = ScoringModel(scoreLogs: repo, labelsRepository: labels, scoreLogId: log.id)

        XCTAssertTrue(model.labels.isEmpty)
        XCTAssertTrue(model.hasAnyLabels)
    }

    func testThePaletteIsWhateverWasCachedOffline() {
        // A sports hall has no signal. The surface reads the cache it is handed and
        // never refreshes on entry.
        let (_, _, model) = fixture()
        XCTAssertEqual(model.labels.map(\.name), ["Good shot", "Forced error"])
    }

    func testUndoClearsATagRowPointingAtAPointThatNoLongerExists() {
        // Otherwise the next label lands on a rally that was taken back.
        let (_, _, model) = fixture()
        model.score(.home)
        model.undo()
        XCTAssertNil(model.pendingTagOrdinal)
        XCTAssertEqual(model.match?.points.count, 0)
        XCTAssertFalse(model.canUndo)
    }

    func testUndoTakesBackATaggedRallyInOneTap() {
        // The complaint this answers: with the rally tagged twice, the old undo
        // needed three taps, and the first two left the score exactly as it was.
        let (_, _, model) = fixture()
        model.score(.home)
        model.score(.away)
        model.toggleTag(ordinal: 1, label: goodShot)
        model.toggleTag(ordinal: 1, label: forcedError)
        model.undo()
        XCTAssertEqual(model.match?.currentGame.home, 1)
        XCTAssertEqual(model.match?.currentGame.away, 0)
    }

    func testUndoPutsTheTagRowBackOnTheRallyBefore() {
        // The board goes back to exactly how it looked after that rally, so the
        // next undo, and the chips, are about the rally the caption names.
        let (_, _, model) = fixture()
        model.score(.home)
        model.toggleTag(ordinal: 0, label: goodShot)
        model.score(.away)
        model.undo()
        XCTAssertEqual(model.pendingTagOrdinal, 0)
        XCTAssertEqual(model.match?.points[0].tags.map(\.labelName), ["Good shot"])
    }

    func testUndoAnnouncesWhatItTookBackAndOffersRedo() {
        let (_, _, model) = fixture()
        model.score(.away)
        model.toggleTag(ordinal: 0, label: forcedError)
        model.undo()
        XCTAssertEqual(model.undoneNotice, "Point to Away undone")
        model.redo()
        XCTAssertNil(model.undoneNotice)
        XCTAssertEqual(model.pendingTagOrdinal, 0)
        XCTAssertEqual(model.match?.points.map(\.wonBy), [Side.away])
        XCTAssertEqual(model.match?.points[0].tags.map(\.labelName), ["Forced error"])
    }

    func testTheNextRallyRetiresTheRedo() {
        // Redo after another rally would put the old log back over that rally.
        let (_, _, model) = fixture()
        model.score(.home)
        model.undo()
        model.score(.away)
        XCTAssertNil(model.undoneNotice)
        model.redo()
        XCTAssertEqual(model.match?.points.map(\.wonBy), [Side.away])
    }

    func testAChangeMadeElsewhereRetiresTheRedoToo() {
        // A sync, or the match page, can change the log under the board.
        let (repo, id, model) = fixture()
        model.score(.home)
        model.score(.home)
        model.undo()
        XCTAssertEqual(model.undoneNotice, "Point to Home undone")
        repo.replaceEvents(id: id, events: [])
        model.reload()
        XCTAssertNil(model.undoneNotice)
    }

    func testTheUndoNoticeCanBeDismissed() {
        let (_, _, model) = fixture()
        model.score(.home)
        model.undo()
        model.dismissUndoNotice()
        XCTAssertNil(model.undoneNotice)
    }

    func testEditingTheScoreSavesItAndMovesTheTagRowToTheLastRally() {
        // As after a tap: a rally just added can be tagged straight away.
        let (_, _, model) = fixture()
        model.score(.home)
        model.editScore(home: 3, away: 2, nextServer: .away)
        XCTAssertEqual(model.match?.currentGame, SideScore(home: 3, away: 2))
        XCTAssertEqual(model.match?.server, .away)
        XCTAssertEqual(model.pendingTagOrdinal, 4)
    }

    func testAnEditRetiresAPendingRedo() {
        // Putting the old log back after an edit would erase the edit.
        let (_, _, model) = fixture()
        model.score(.home)
        model.score(.home)
        model.undo()
        model.editScore(home: 4, away: 0, nextServer: nil)
        XCTAssertNil(model.undoneNotice)
    }

    func testAScoreThatCannotBeReachedIsNotSaved() {
        let (_, _, model) = fixture()
        model.score(.home)
        model.editScore(home: 30, away: 2, nextServer: nil)
        XCTAssertEqual(model.match?.currentGame, SideScore(home: 1, away: 0))
        XCTAssertEqual(model.pendingTagOrdinal, 0)
    }

    func testFinishingEndsTheMatchAndClosesTheRow() {
        let (repo, id, model) = fixture()
        model.score(.home)
        model.finish()
        XCTAssertEqual(repo.get(id: id)?.status, .unbound)
        XCTAssertNil(model.pendingTagOrdinal)
    }

    func testADeletedMatchLeavesTheScreenInertRatherThanCrashing() {
        // Reachable: the match was swiped away in the list on another screen while
        // this one was still open.
        let (repo, id, model) = fixture()
        repo.removeLocally(id: id)
        model.reload()
        XCTAssertNil(model.match)
        XCTAssertFalse(model.canScore)
        model.score(.home)
        model.toggleTag(ordinal: 0, label: goodShot)
        model.finish()
        XCTAssertNil(model.match)
    }

    func testResettingTheCurrentGameLeavesFinishedGamesAlone() {
        // The coach mis-scored the third game, not the first two.
        let (_, _, model) = fixture(rules: ScoringRules(
            pointsToWin: 2, winBy: 1, cap: nil, intervalAt: nil, gamesToWin: 2, changeEndsAt: nil
        ))
        model.score(.home)
        model.score(.home)          // home takes game 1
        model.score(.away)          // one point into game 2
        model.toggleTag(ordinal: 2, label: goodShot)
        model.resetCurrentGame()
        XCTAssertEqual(model.match?.gamesWon.home, 1)
        XCTAssertEqual(model.match?.currentGame.home, 0)
        XCTAssertEqual(model.match?.points.map { Int($0.ordinal) }, [0, 1])
        XCTAssertNil(model.pendingTagOrdinal)
    }

    func testUndoTakesBackTheLastRallyAndLeavesTheNoteOnTheOneBefore() {
        // Undo cuts by rally, so a note on an earlier rally is not what it takes.
        let (_, _, model) = fixture()
        model.score(.home)
        model.setComment(ordinal: 0, text: "net cord")
        model.score(.away)
        model.undo()
        XCTAssertEqual(model.match?.points.map { Int($0.ordinal) }, [0])
        XCTAssertEqual(model.match?.points[0].comment, "net cord")
    }

    func testAFinishedMatchStopsTakingTapsButCanStillBeUndone() {
        // A match can end on a mis-tap, and that is exactly when undo matters most.
        let (_, _, model) = fixture(rules: ScoringRules(
            pointsToWin: 1, winBy: 1, cap: nil, intervalAt: nil, gamesToWin: 1, changeEndsAt: nil
        ))
        model.score(.home)
        XCTAssertFalse(model.canScore)
        XCTAssertTrue(model.canUndo)
        model.score(.away)
        XCTAssertEqual(model.match?.points.map(\.wonBy), [Side.home])
        model.undo()
        XCTAssertTrue(model.canScore)
        XCTAssertEqual(model.match?.points.count, 0)
    }

    // MARK: - start() and the `.task` that runs it

    func testLeavingTheBoardStopsEveryLoopItStarted() async {
        // With a labels repository - which is what the app hands over and what
        // `fixture` does not - start() spawned two unstructured `Task {}` loops
        // that nothing cancelled, and `ScoringView` built a fresh model on every
        // run of its `.task`, so each return to the board left two more behind.
        // See `MatchModelTests` for why a released model is the probe.
        //
        // A real `RallyApp` for the repository: shared has no double for it, and
        // both flows read are its local cache, so nothing reaches the network.
        let rally = RallyAppIosKt.createRallyApp(
            url: "https://test.supabase.co",
            anonKey: "test-anon-key",
            deleteLocalVideoFile: { _ in },
            deleteLocalAnalysis: { _, _ in }
        )
        let (repo, id, _) = fixture()
        var m: ScoringModel? = ScoringModel(scoreLogs: repo, labelsRepository: rally.labels, scoreLogId: id)
        weak var released = m

        let returned = await ObservationLifecycle.runAndCancel { [m] in await m?.start() }
        XCTAssertTrue(returned, "start() did not return after its task was cancelled")

        m = nil
        let gone = await ObservationLifecycle.wait { released == nil }
        XCTAssertTrue(gone, "a loop start() spawned is still running and still holds the model")
    }

    func testComingBackToTheBoardFollowsTheStoreAgain() async {
        let (repo, id, model) = fixture()

        let returned = await ObservationLifecycle.runAndCancel { await model.start() }
        XCTAssertTrue(returned, "start() did not return after its task was cancelled")

        let again = Task { await model.start() }
        defer { again.cancel() }
        // Straight to the store, not through the model, so only the loop can
        // have carried it across.
        repo.replaceEvents(id: id, events: [ScoreEventPointTo(side: .home)])

        let noticed = await ObservationLifecycle.wait { model.match?.currentGame.home == 1 }
        XCTAssertTrue(noticed, "the second start() did not pick the store back up")
    }
}

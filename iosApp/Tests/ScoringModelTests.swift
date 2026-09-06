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
            homePlayers: ["Coen"],
            awayPlayers: ["Marco"],
            rules: rules,
            setup: MatchSetup(doubles: false, firstServer: .home, homeStartsRight: .first, awayStartsRight: .first)
        )
        let model = ScoringModel(scoreLogs: repo, scoreboardLabels: [goodShot, forcedError], scoreLogId: log.id)
        return (repo, log.id, model)
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
            homePlayers: ["Coen"],
            awayPlayers: ["Marco"],
            rules: ScoringRules.companion.BWF_21,
            setup: MatchSetup(doubles: false, firstServer: .home, homeStartsRight: .first, awayStartsRight: .first)
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
            homePlayers: ["Coen"],
            awayPlayers: ["Marco"],
            rules: ScoringRules.companion.BWF_21,
            setup: MatchSetup(doubles: false, firstServer: .home, homeStartsRight: .first, awayStartsRight: .first)
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

    func testANoteIsOneEntrySoUndoTakesBackARallyAndNotALetter() {
        // Undo drops the last log entry. A note written a character at a time would
        // be a character at a time to take back, which would cost the coach the one
        // control he reaches for mid-rally. The surface commits the note once.
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
}

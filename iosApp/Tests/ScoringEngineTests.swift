import XCTest
import Shared
@testable import iosApp

/// Proves the scoring engine is usable from Swift before L1's scoring surface is
/// built on top of it. Correctness of the fold is covered by the Kotlin suite;
/// what is tested here is that the types survive the bridge.
final class ScoringEngineTests: XCTestCase {

    private let doubles = MatchSetup(
        doubles: true,
        firstServer: .home,
        homeStartsRight: .first,
        awayStartsRight: .first
    )

    private func fold(_ events: [ScoreEvent]) -> MatchState {
        foldMatchState(rules: ScoringRules.companion.BWF_21, setup: doubles, events: events)
    }

    func testAPresetCrossesTheBridge() {
        XCTAssertEqual(ScoringRules.companion.BWF_21.pointsToWin, 21)
        XCTAssertEqual(ScoringRules.companion.PRESETS.count, 3)
    }

    func testFoldingADoublesLogGivesTheServeAndTheScore() {
        let state = fold([
            ScoreEventPointTo(side: .home),
            ScoreEventPointTo(side: .home),
            ScoreEventPointTo(side: .away),
        ])
        XCTAssertEqual(state.currentGame.home, 2)
        XCTAssertEqual(state.currentGame.away, 1)
        XCTAssertEqual(state.server, .away)
        XCTAssertEqual(state.serviceCourt, .left)
        XCTAssertEqual(state.servingPlayer, .second)
        XCTAssertEqual(state.receivingPlayer, .second)
        XCTAssertEqual(state.pointCount, 3)
        XCTAssertFalse(state.isOver)
    }

    func testATaggedPointArrivesWithItsLabelSnapshot() {
        let state = fold([
            ScoreEventPointTo(side: .home),
            ScoreEventTagPoint(
                pointOrdinal: 0,
                tags: [PointTag(labelName: "Forced error", labelColor: "amber")],
                comment: "pushed wide"
            ),
        ])
        XCTAssertEqual(state.points.first?.tags.first?.labelName, "Forced error")
        XCTAssertEqual(state.points.first?.tags.first?.labelColor, "amber")
        XCTAssertEqual(state.points.first?.comment, "pushed wide")
    }

    func testAnInvalidRuleSetIsReportedRatherThanThrown() {
        // A Kotlin exception crossing this bridge aborts the app, so the validator
        // is the only thing Swift is ever allowed to construct rules through.
        // A nullable Kotlin Int arrives as KotlinInt?, which is why these are
        // wrapped rather than written as plain literals.
        let problem = scoringRulesProblem(
            pointsToWin: 21, winBy: 2, cap: KotlinInt(int: 5),
            intervalAt: nil, gamesToWin: 2, changeEndsAt: nil
        )
        XCTAssertEqual(problem, "The cap cannot be below the target score.")
        XCTAssertNil(
            scoringRulesProblem(
                pointsToWin: 21, winBy: 2, cap: KotlinInt(int: 30),
                intervalAt: KotlinInt(int: 11), gamesToWin: 2, changeEndsAt: KotlinInt(int: 11)
            )
        )
    }

    func testUndoIsReFoldingAShorterLog() {
        let log: [ScoreEvent] = [
            ScoreEventPointTo(side: .home),
            ScoreEventPointTo(side: .home),
        ]
        let undone = foldMatchState(
            rules: ScoringRules.companion.BWF_21,
            setup: doubles,
            events: ScoreEventKt.undoLast(log)
        )
        XCTAssertEqual(undone.currentGame.home, 1)
    }
}

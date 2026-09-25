import XCTest
import Shared
@testable import iosApp

/// Proves the scoring engine is usable from Swift before L1's scoring surface is
/// built on top of it. Correctness of the fold is covered by the Kotlin suite;
/// what is tested here is that the types survive the bridge.
final class ScoringEngineTests: XCTestCase {

    private let homeServes = MatchSetup(firstServer: .home)

    private func fold(_ events: [ScoreEvent]) -> MatchState {
        foldMatchState(rules: ScoringRules.companion.BWF_21, setup: homeServes, events: events)
    }

    func testAPresetCrossesTheBridge() {
        XCTAssertEqual(ScoringRules.companion.BWF_21.pointsToWin, 21)
    }

    func testTheFormsOptionsCrossTheBridgeInPickerOrder() {
        XCTAssertEqual(GamePoints.allCases.map(\.pointsToWin), [15, 21, 11])
        XCTAssertEqual(MatchSets.allCases.map(\.sets), [3, 1])
        XCTAssertEqual(GamePoints.companion.DEFAULT, .twentyOne)
        XCTAssertEqual(MatchSets.companion.DEFAULT, .three)
        let rules = ScoringRules.companion.of(points: .eleven, sets: .one)
        XCTAssertEqual(rules.pointsToWin, 11)
        XCTAssertEqual(rules.gamesToWin, 1)
    }

    func testTheDefaultNameCrossesTheBridge() {
        // The zone is the machine's, so only the shape is pinned here; the Kotlin
        // suite pins the text against a fixed zone.
        let name = DefaultMatchTitleKt.defaultMatchTitle(epochMs: 1_789_907_400_000, place: "Amsterdam")
        XCTAssertTrue(name.hasSuffix(" - Amsterdam"), name)
        XCTAssertNil(ScoreLogKt.createMatchProblem(title: name))
    }

    func testTheDetailsEditorsRulesCrossTheBridge() {
        XCTAssertEqual(MatchFormat.allCases.map(\.shortLabel), ["Singles", "Doubles", "Mixed"])
        XCTAssertEqual(MatchDetailsKt.playersPerSide(format: nil), 1)
        XCTAssertEqual(MatchDetailsKt.playersPerSide(format: .doubles), 2)
        XCTAssertNil(MatchDetailsKt.matchDetailsProblem(title: "Thu", homePlayers: [], awayPlayers: []))
        XCTAssertEqual(
            MatchDetailsKt.matchDetailsProblem(title: " ", homePlayers: ["Coen"], awayPlayers: []),
            "Give the match a name."
        )
    }

    func testASideIsNamedForWhereItStands() {
        XCTAssertEqual(Side.home.label, "Home")
        XCTAssertEqual(Side.away.label, "Away")
    }

    func testFoldingALogGivesTheServeAndTheScore() {
        let state = fold([
            ScoreEventPointTo(side: .home),
            ScoreEventPointTo(side: .home),
            ScoreEventPointTo(side: .away),
        ])
        XCTAssertEqual(state.currentGame.home, 2)
        XCTAssertEqual(state.currentGame.away, 1)
        XCTAssertEqual(state.server, .away)
        XCTAssertEqual(state.serviceCourt, .left)
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
            setup: homeServes,
            events: ScoreEventKt.undoLastPoint(log)
        )
        XCTAssertEqual(undone.currentGame.home, 1)
    }
}

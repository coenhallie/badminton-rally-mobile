import XCTest
@testable import iosApp

/// HomeView's path rules: which screens count as a list in front, where the
/// background-work indicator goes, and the in-place swaps of the
/// create-and-finish flow.
final class HomeRouteTests: XCTestCase {

    private let match = HomeRoute.match(MatchRoute(scoreLogId: "log-1", videoId: nil))

    func testPlayersAndAllMatchesAreTheListScreens() {
        XCTAssertTrue(HomePath.listInFront([]))
        XCTAssertTrue(HomePath.listInFront([.allMatches]))
        XCTAssertFalse(HomePath.listInFront([.allMatches, match]))
        XCTAssertFalse(HomePath.listInFront([.settings]))
        XCTAssertFalse(HomePath.listInFront([.scoring(scoreLogId: "log-1")]))
    }

    func testTheIndicatorGoesBackToTheAllMatchesAlreadyOnTheStack() {
        let path: [HomeRoute] = [.allMatches, match, .courtMarking(CourtMarkingRoute(entryId: "e-1"))]
        XCTAssertEqual(HomePath.toAllMatches(path), [.allMatches])
        XCTAssertEqual(HomePath.toAllMatches([.allMatches]), [.allMatches])
    }

    func testTheIndicatorPutsAllMatchesOverPlayersFromAnywhereElse() {
        XCTAssertEqual(HomePath.toAllMatches([]), [.allMatches])
        XCTAssertEqual(HomePath.toAllMatches([.settings]), [.allMatches])
        XCTAssertEqual(HomePath.toAllMatches([match]), [.allMatches])
    }

    func testNewMatchGivesWayToItsBoardInPlace() {
        let path = HomePath.replacing(.newMatch, with: .scoring(scoreLogId: "log-1"), in: [.newMatch])
        XCTAssertEqual(path, [.scoring(scoreLogId: "log-1")])
    }

    func testTheFinishedBoardBecomesItsMatchPageInPlace() {
        let finished = HomeRoute.finished(scoreLogId: "log-1", attach: .record)
        let path = HomePath.replacing(.scoring(scoreLogId: "log-1"), with: finished, in: [.scoring(scoreLogId: "log-1")])
        XCTAssertEqual(path, [finished])
    }

    func testASwapWhoseScreenHasGonePushesInstead() {
        let finished = HomeRoute.finished(scoreLogId: "log-1", attach: nil)
        XCTAssertEqual(
            HomePath.replacing(.scoring(scoreLogId: "log-1"), with: finished, in: [.allMatches]),
            [.allMatches, finished]
        )
    }
}

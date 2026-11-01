import XCTest
@testable import iosApp

/// HomeView's path rules: which screens count as a list in front, where the
/// background-work indicator goes, the one Player entry, and the in-place
/// swaps of the create-and-finish flow.
final class HomeRouteTests: XCTestCase {

    private let match = HomeRoute.match(MatchRoute(scoreLogId: "log-1", videoId: nil))

    func testPlayersAllMatchesAndAPlayersPageAreTheListScreens() {
        XCTAssertTrue(HomePath.listInFront([]))
        XCTAssertTrue(HomePath.listInFront([.allMatches]))
        XCTAssertTrue(HomePath.listInFront([.player(id: "p-marco")]))
        XCTAssertFalse(HomePath.listInFront([.allMatches, match]))
        XCTAssertFalse(HomePath.listInFront([.player(id: "p-marco"), match]))
        XCTAssertFalse(HomePath.listInFront([.player(id: "p-marco"), .newMatch(playerId: "p-marco")]))
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

    func testTheIndicatorTakesAPlayersPageAndWhatIsOverItAway() {
        // All matches is never pushed over a player's page, so the page and
        // everything above it go, and All matches sits on Players.
        XCTAssertEqual(HomePath.toAllMatches([.player(id: "p-marco")]), [.allMatches])
        XCTAssertEqual(HomePath.toAllMatches([.player(id: "p-marco"), match]), [.allMatches])
    }

    // MARK: - A player's page

    func testOpeningAPlayerPushesTheirPageOverPlayers() {
        XCTAssertEqual(HomePath.toPlayer("p-marco", in: []), [.player(id: "p-marco")])
    }

    func testSwitchingPlayerReplacesThePageInPlace() {
        XCTAssertEqual(
            HomePath.toPlayer("p-li", in: [.player(id: "p-marco")]),
            [.player(id: "p-li")],
            "the switcher grew the stack: Back would land on the player switched away from"
        )
    }

    func testOpeningThePlayerAlreadyShownKeepsOnePage() {
        // A second tap on a row while its push is still animating.
        XCTAssertEqual(HomePath.toPlayer("p-marco", in: [.player(id: "p-marco")]), [.player(id: "p-marco")])
    }

    func testAPlayerWhoHasGoneClosesTheirPageBackToPlayers() {
        XCTAssertEqual(HomePath.closingPlayer("p-marco", in: [.player(id: "p-marco")]), [])
    }

    func testAPlayerWhoHasGoneUnderAPushWaitsUntilTheirPageIsInFront() {
        // A page still under a match must not pop the match from under the coach.
        let path: [HomeRoute] = [.player(id: "p-marco"), match]
        XCTAssertEqual(HomePath.closingPlayer("p-marco", in: path), path)
        // Nor may a page that has already given way to another player's close it.
        XCTAssertEqual(HomePath.closingPlayer("p-marco", in: [.player(id: "p-li")]), [.player(id: "p-li")])
    }

    func testNewMatchGivesWayToItsBoardInPlace() {
        let path = HomePath.replacing(
            .newMatch(playerId: nil), with: .scoring(scoreLogId: "log-1"), in: [.newMatch(playerId: nil)]
        )
        XCTAssertEqual(path, [.scoring(scoreLogId: "log-1")])
    }

    func testANewMatchForAPlayerGivesWayToItsBoardOverTheirPage() {
        let marco = HomeRoute.player(id: "p-marco")
        let path = HomePath.replacing(
            .newMatch(playerId: "p-marco"),
            with: .scoring(scoreLogId: "log-1"),
            in: [marco, .newMatch(playerId: "p-marco")]
        )
        XCTAssertEqual(path, [marco, .scoring(scoreLogId: "log-1")])
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

import XCTest
import Shared
@testable import iosApp

/// The parity check. These are `MatchViewModelTest`'s cases, name for name: if
/// the two lists diverge, the two surfaces have diverged.
///
/// Unlike the Android suite, none of these need a dispatcher: `MatchModel` reads
/// the store with `ScoreLogsRepository.get(id:)`, a direct cache lookup rather
/// than a flow, so a mutation made after construction is visible the moment
/// `reload()` (or `readStore()` inside it) runs - no coroutine to pump.
@MainActor
final class MatchModelTests: XCTestCase {

    // 2026-08-28T18:00:00Z. Fixed, because the store stamps created_at from it.
    private let t0 = KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: 1_787_940_000_000)

    private func store() -> ScoreLogsRepository {
        SwiftInteropKt.testScoreLogsRepository(now: t0, ownerId: "owner-1")
    }

    private func newMatch(_ store: ScoreLogsRepository) -> ScoreLog {
        store.create(
            title: "Thu League",
            homePlayers: ["Coen"],
            awayPlayers: ["Marco"],
            rules: ScoringRules.companion.BWF_21,
            setup: MatchSetup(doubles: false, firstServer: .home, homeStartsRight: .first, awayStartsRight: .first)
        )
    }

    func testAMatchStillBeingScoredCannotTakeAVideoYet() {
        // Its action is Score/Resume. Offering both would put "add the video"
        // beside a match that has not been played, and would need .live and
        // .bound to mean something together.
        let store = store()
        let log = newMatch(store)
        let model = MatchModel(scoreLogs: store, scoreLogId: log.id)
        XCTAssertFalse(model.canAddVideo)
    }

    func testAMatchTheRulesHaveEndedCanTakeAVideoEvenWhileStillMarkedLive() {
        let store = store()
        let log = newMatch(store)
        store.replaceEvents(id: log.id, events: Array(repeating: ScoreEventPointTo(side: .home), count: 42))
        let model = MatchModel(scoreLogs: store, scoreLogId: log.id)
        XCTAssertTrue(model.canAddVideo)
    }

    func testAMatchEndedByHandCanTakeAVideo() {
        let store = store()
        let log = newMatch(store)
        store.finish(id: log.id)
        let model = MatchModel(scoreLogs: store, scoreLogId: log.id)
        XCTAssertTrue(model.canAddVideo)
    }

    func testAMatchThatAlreadyHasAVideoIsNotOfferedAnotherOne() {
        // Replacing means removing the first, which is the delete gesture, not a
        // second picker on the same page.
        let store = store()
        let log = newMatch(store)
        store.finish(id: log.id)
        store.attachVideo(id: log.id, videoId: "vid-1")
        let model = MatchModel(scoreLogs: store, scoreLogId: log.id)
        XCTAssertFalse(model.canAddVideo)
    }

    func testADeletedMatchLeavesThePageInertRatherThanCrashing() {
        let store = store()
        let log = newMatch(store)
        let model = MatchModel(scoreLogs: store, scoreLogId: log.id)
        store.removeLocally(id: log.id)
        model.reload()
        XCTAssertNil(model.log)
        XCTAssertFalse(model.canAddVideo)
    }
}

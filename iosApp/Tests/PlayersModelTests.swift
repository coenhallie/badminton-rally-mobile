import Shared
import XCTest
@testable import iosApp

/// The Players screen's model, held to what androidApp's PlayersViewModel
/// promises: a failed load says so rather than reading as "no players yet",
/// a create hands back the player or the sentence for the sheet that asked,
/// and leaving the screen stops its loop.
///
/// Built on a real `RallyApp` for the one thing it supplies here, the
/// constructor's default stores; every store the model reads or writes is a
/// double.
@MainActor
final class PlayersModelTests: XCTestCase {

    private var rally: RallyApp!

    // 2026-08-28T18:00:00Z. Fixed, because the score log store stamps from it.
    private let t0 = KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: 1_787_940_000_000)

    override func setUp() {
        rally = RallyAppIosKt.createRallyApp(
            url: "https://test.supabase.co",
            anonKey: "test-anon-key",
            deleteLocalVideoFile: { _ in },
            deleteLocalAnalysis: { _, _ in }
        )
    }

    private var marco: Player {
        Player(id: "p-marco", ownerId: "owner-1", name: "Marco", racketArm: nil, createdAt: t0, updatedAt: t0)
    }

    private func model(_ players: PlayersRepository) -> PlayersModel {
        PlayersModel(
            rally: rally,
            players: players,
            scoreLogs: SwiftInteropKt.testScoreLogsRepository(now: t0, ownerId: "owner-1"),
            localVideos: IosTestDoublesKt.testLocalVideoRepository(),
            currentUserId: { "owner-1" }
        )
    }

    func testNothingIsSaidAboutAnEmptyListBeforeTheFirstRefresh() async {
        // "Add your first player" on a fresh install whose players are still
        // on their way would invite a duplicate.
        let m = model(PlayersTestDoublesKt.testPlayersRepository(players: []))
        XCTAssertFalse(m.showsNoPlayers)

        await m.refresh()

        XCTAssertTrue(m.showsNoPlayers)
        XCTAssertFalse(m.loadFailed)
        XCTAssertNil(m.errorMessage)
    }

    func testRefreshFailureSetsLoadFailed() async {
        let m = model(PlayersTestDoublesKt.testOfflinePlayersRepository(players: []))

        await m.refresh()

        XCTAssertTrue(m.loadFailed)
        XCTAssertTrue(m.showsNoPlayers, "a failed load settles the first refresh too")
        XCTAssertEqual(m.errorMessage, PlayersCopy.shared.LOAD_FAILED)
    }

    func testAShownMessageIsSaidOnceButTheFailureStays() async {
        // The toast takes the sentence; the empty list still has to say the
        // load failed once the toast has gone.
        let m = model(PlayersTestDoublesKt.testOfflinePlayersRepository(players: []))
        await m.refresh()

        m.errorShown()

        XCTAssertNil(m.errorMessage)
        XCTAssertTrue(m.loadFailed)
    }

    func testCreateReturnsThePlayer() async {
        let m = model(PlayersTestDoublesKt.testPlayersRepository(players: []))

        let result = await m.create(name: "  Lena Smit ", arm: .left)

        guard case .created(let player) = result else {
            return XCTFail("expected a created player, got \(result)")
        }
        XCTAssertEqual(player.name, "Lena Smit")
        XCTAssertEqual(player.racketArm, .left)
    }

    func testACreateTheServerRefusesHandsBackTheSentence() async {
        let m = model(PlayersTestDoublesKt.testOfflinePlayersRepository(players: []))

        let result = await m.create(name: "Lena", arm: nil)

        guard case .failed(let message) = result else {
            return XCTFail("expected a failure, got \(result)")
        }
        XCTAssertEqual(message, PlayersCopy.shared.SAVE_FAILED)
        XCTAssertNil(m.errorMessage, "the sheet that asked says it, not the toast")
    }

    func testABlankNameIsRefusedWithItsOwnSentence() async {
        let m = model(PlayersTestDoublesKt.testPlayersRepository(players: []))

        let result = await m.create(name: "   ", arm: nil)

        guard case .failed(let message) = result else {
            return XCTFail("expected a failure, got \(result)")
        }
        XCTAssertEqual(message, PlayersCopy.shared.NAME_REQUIRED)
    }

    func testRenameAnswersWithTheFailureSentenceOrNil() async {
        let online = model(PlayersTestDoublesKt.testPlayersRepository(players: [marco]))
        let saved = await online.rename(id: "p-marco", name: "Marco Vidal")
        XCTAssertNil(saved)

        let offline = model(PlayersTestDoublesKt.testOfflinePlayersRepository(players: [marco]))
        let refused = await offline.rename(id: "p-marco", name: "Marco Vidal")
        XCTAssertEqual(refused, PlayersCopy.shared.SAVE_FAILED)
    }

    func testADeleteTheServerRefusesSaysSo() async {
        let m = model(PlayersTestDoublesKt.testOfflinePlayersRepository(players: [marco]))

        let deleted = await m.delete(marco)

        XCTAssertFalse(deleted)
        XCTAssertEqual(m.errorMessage, PlayersCopy.shared.DELETE_FAILED)
    }

    func testADeleteThatLandsReportsSuccess() async {
        let m = model(PlayersTestDoublesKt.testPlayersRepository(players: [marco]))

        let deleted = await m.delete(marco)

        XCTAssertTrue(deleted)
        XCTAssertNil(m.errorMessage)
    }

    func testObservingFollowsTheRepository() async {
        let repo = PlayersTestDoublesKt.testPlayersRepository(players: [marco])
        let m = model(repo)
        let loop = Task { @MainActor in await m.observe() }
        defer { loop.cancel() }

        let seen = await ObservationLifecycle.wait { m.players.map(\.id) == ["p-marco"] }
        XCTAssertTrue(seen, "observe() did not deliver the repository's list")

        _ = await m.create(name: "Lena", arm: nil)
        let added = await ObservationLifecycle.wait { m.players.count == 2 }
        XCTAssertTrue(added, "a created player did not reach the list")
    }

    func testLeavingStopsObserving() async {
        // See `ObservationLifecycle`: a model still alive after its only owner
        // let go is one a loop is still holding.
        var m: PlayersModel? = model(PlayersTestDoublesKt.testPlayersRepository(players: [marco]))
        weak var released = m

        let returned = await ObservationLifecycle.runAndCancel { [m] in await m?.observe() }
        XCTAssertTrue(returned, "observe() did not return after its task was cancelled")

        m = nil
        let gone = await ObservationLifecycle.wait { released == nil }
        XCTAssertTrue(gone, "observe() is still running and still holds the model")
    }
}

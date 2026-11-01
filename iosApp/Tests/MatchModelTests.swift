import XCTest
import Shared
@testable import iosApp

/// The parity check. These are `MatchViewModelTest`'s cases, name for name: if
/// the two lists diverge, the two surfaces have diverged.
///
/// The first six need no dispatcher: `MatchModel` reads the store with
/// `ScoreLogsRepository.get(id:)`, a direct cache lookup rather than a flow, so a
/// mutation made before construction is visible the moment `init` runs. The last
/// drives its mutation through `start()`'s `for await` loop instead - the path
/// `MatchView`'s `.task` actually runs - because that loop, not a direct read, is
/// what the app relies on to notice a match removed elsewhere.
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
            rules: ScoringRules.companion.BWF_21,
            setup: MatchSetup(firstServer: .home)
        )
    }

    /// Nothing under test here exercises the attach pipeline itself, so a fresh
    /// `testLocalVideoRepository()` (empty, or carrying one hand-added entry) is
    /// enough.
    private func model(
        scoreLogs: ScoreLogsRepository,
        scoreLogId: String?,
        localVideos: LocalVideoRepository = IosTestDoublesKt.testLocalVideoRepository(),
        videos: RecordingVideosRepository = IosTestDoublesKt.testVideosRepository(),
        players: PlayersRepository? = nil
    ) -> MatchModel {
        MatchModel(
            scoreLogs: scoreLogs, localVideos: localVideos, clips: IosTestDoublesKt.testClipsRepository(),
            videos: videos, localAnnotations: IosTestDoublesKt.testLocalAnnotationsRepository(),
            players: players, scoreLogId: scoreLogId
        )
    }

    private func entry(
        scoreLogId: String, id: String = "e1", stage: AnalyzeStage = .local
    ) -> LocalVideoEntry {
        LocalVideoEntry(
            id: id, uri: "content://x/\(id)", displayName: "m.mp4",
            durationMs: 1000, sizeBytes: 10, addedAtEpochMs: 0,
            title: nil, description: nil, keypoints: nil,
            stage: stage, failedStep: nil, failureMessage: nil, resultSeen: false,
            scoreLogId: scoreLogId,
            homePlayerId: nil, awayPlayerId: nil, homePlayers: [], awayPlayers: [], nearSide: nil
        )
    }

    func testAMatchStillBeingScoredCannotTakeAVideoYet() {
        // Its action is Score/Resume. Offering both would put "add the video"
        // beside a match that has not been played, and would need .live and
        // .bound to mean something together.
        let store = store()
        let log = newMatch(store)
        let m = model(scoreLogs: store, scoreLogId: log.id)
        XCTAssertFalse(m.canAddVideo)
    }

    func testAMatchTheRulesHaveEndedCanTakeAVideoEvenWhileStillMarkedLive() {
        let store = store()
        let log = newMatch(store)
        store.replaceEvents(id: log.id, events: Array(repeating: ScoreEventPointTo(side: .home), count: 42))
        let m = model(scoreLogs: store, scoreLogId: log.id)
        XCTAssertTrue(m.canAddVideo)
    }

    func testAProfileWinnerIsNamedByThePlayerAndFollowsARename() async throws {
        // The card's status line and the page's players line read the side a
        // profile holds from the players, so a rename reaches this page live.
        let marco = Player(id: "p-marco", ownerId: "owner-1", name: "Marco", racketArm: nil, createdAt: t0, updatedAt: t0)
        let players = PlayersTestDoublesKt.testPlayersRepository(players: [marco])
        let store = store()
        let log = newMatch(store)
        store.updateDetails(
            id: log.id, title: "Thu League", format: nil,
            players: MatchPlayers(homePlayerId: "p-marco", awayPlayerId: nil, homePlayers: [], awayPlayers: ["Li"])
        )
        store.replaceEvents(id: log.id, events: Array(repeating: ScoreEventPointTo(side: .home), count: 42))
        let m = model(scoreLogs: store, scoreLogId: log.id, players: players)
        XCTAssertEqual(m.card?.statusLine, "Marco won")
        XCTAssertEqual(m.card?.playersLine, "Marco vs Li")

        let loops = Task { await m.start() }
        defer { loops.cancel() }
        _ = try await players.rename(id: "p-marco", name: "Marco Rossi")
        let renamed = await ObservationLifecycle.wait { m.card?.statusLine == "Marco Rossi won" }
        XCTAssertTrue(renamed, "the page did not follow the rename")
        XCTAssertEqual(m.players.map(\.name), ["Marco Rossi"])
    }

    func testAMatchEndedByHandCanTakeAVideo() {
        let store = store()
        let log = newMatch(store)
        store.finish(id: log.id)
        let m = model(scoreLogs: store, scoreLogId: log.id)
        XCTAssertTrue(m.canAddVideo)
    }

    func testAMatchThatAlreadyHasAVideoIsNotOfferedAnotherOne() {
        // One video per match: replacing it is "Change video", which removes the
        // first and then reuses this same picker, rather than a second picker
        // standing open beside the video already attached.
        let store = store()
        let log = newMatch(store)
        store.finish(id: log.id)
        store.attachVideo(id: log.id, videoId: "vid-1")
        let m = model(scoreLogs: store, scoreLogId: log.id)
        XCTAssertFalse(m.canAddVideo)
    }

    func testAMatchWhoseVideoIsStillBeingPickedUpIsNotOfferedAnotherOne() {
        // Wired through localVideos: an entry can exist for a while before the
        // pipeline gives the match a videoId (LOCAL, UPLOADING, PROCESSING).
        // Gating canAddVideo on videoId alone would let the picker reopen while
        // an attach for this same match is already in flight.
        let store = store()
        let log = newMatch(store)
        store.finish(id: log.id)
        let videos = IosTestDoublesKt.testLocalVideoRepository()
        videos.add(entry: LocalVideoEntry(
            id: "e1", uri: "content://x/e1", displayName: "m.mp4",
            durationMs: 1000, sizeBytes: 10, addedAtEpochMs: 0,
            title: nil, description: nil, keypoints: nil,
            stage: .local, failedStep: nil, failureMessage: nil, resultSeen: false,
            scoreLogId: log.id,
            homePlayerId: nil, awayPlayerId: nil, homePlayers: [], awayPlayers: [], nearSide: nil
        ))
        let m = model(scoreLogs: store, scoreLogId: log.id, localVideos: videos)
        XCTAssertFalse(m.canAddVideo)
    }

    func testAMatchWithNoVideoIsOfferedNeitherChangeNorRemove() {
        let store = store()
        let log = newMatch(store)
        store.finish(id: log.id)
        XCTAssertFalse(model(scoreLogs: store, scoreLogId: log.id).canRemoveVideo)
    }

    func testABoundMatchCanChangeOrRemoveItsVideo() {
        let store = store()
        let log = newMatch(store)
        store.finish(id: log.id)
        store.attachVideo(id: log.id, videoId: "vid-1")
        let m = model(scoreLogs: store, scoreLogId: log.id)
        XCTAssertTrue(m.canRemoveVideo)
        XCTAssertTrue(m.hasServerVideo)
        // Mutually exclusive with canAddVideo, on every surface.
        XCTAssertFalse(m.canAddVideo)
    }

    func testAFailedVideoCanBeChangedOrRemoved() {
        // The reported dead end: analysis found no rallies, and Retry over the
        // same file is the only thing the page offered.
        let store = store()
        let log = newMatch(store)
        store.finish(id: log.id)
        store.attachVideo(id: log.id, videoId: "vid-1")
        let videos = IosTestDoublesKt.testLocalVideoRepository()
        videos.add(entry: entry(scoreLogId: log.id, id: "vid-1", stage: .failed))
        XCTAssertTrue(model(scoreLogs: store, scoreLogId: log.id, localVideos: videos).canRemoveVideo)
    }

    func testAVideoStillUploadingCanBeNeitherChangedNorRemoved() {
        let store = store()
        let log = newMatch(store)
        store.finish(id: log.id)
        let videos = IosTestDoublesKt.testLocalVideoRepository()
        videos.add(entry: entry(scoreLogId: log.id, stage: .uploading))
        XCTAssertFalse(model(scoreLogs: store, scoreLogId: log.id, localVideos: videos).canRemoveVideo)
    }

    func testAVideoPickedButNotYetUploadedSaysThereAreNoRalliesToLose() {
        let store = store()
        let log = newMatch(store)
        store.finish(id: log.id)
        let videos = IosTestDoublesKt.testLocalVideoRepository()
        videos.add(entry: entry(scoreLogId: log.id, stage: .local))
        let m = model(scoreLogs: store, scoreLogId: log.id, localVideos: videos)
        XCTAssertTrue(m.canRemoveVideo)
        XCTAssertFalse(m.hasServerVideo)
    }

    func testRemovingAVideoLeavesTheMatchAndReopensAddVideo() async {
        let store = store()
        let log = newMatch(store)
        store.finish(id: log.id)
        store.attachVideo(id: log.id, videoId: "vid-1")
        let entries = IosTestDoublesKt.testLocalVideoRepository()
        entries.add(entry: entry(scoreLogId: log.id, id: "vid-1", stage: .failed))
        let videos = IosTestDoublesKt.testVideosRepository()
        let m = model(scoreLogs: store, scoreLogId: log.id, localVideos: entries, videos: videos)

        let removed = await m.removeVideo()

        XCTAssertTrue(removed)
        XCTAssertEqual(videos.deletedVideoIds, ["vid-1"])
        XCTAssertNil(m.log?.videoId)
        XCTAssertFalse(m.canRemoveVideo)
        // The whole point of "Change video": the attach path is open again with
        // no second implementation of it.
        XCTAssertTrue(m.canAddVideo)
    }

    func testAFailedRemovalSurfacesAMessageAndDoesNotHandOverToThePicker() async {
        let store = store()
        let log = newMatch(store)
        store.finish(id: log.id)
        store.attachVideo(id: log.id, videoId: "vid-1")
        let videos = IosTestDoublesKt.testVideosRepository()
        videos.failDeleteMatch = true
        let m = model(scoreLogs: store, scoreLogId: log.id, videos: videos)

        let removed = await m.removeVideo()

        XCTAssertFalse(removed)
        XCTAssertEqual(m.error, MatchVideoRemovalKt.MATCH_VIDEO_REMOVE_FAILED_MESSAGE)
        XCTAssertEqual(m.log?.videoId, "vid-1")
    }

    func testADeletedMatchLeavesThePageInertRatherThanCrashing() async throws {
        let store = store()
        let log = newMatch(store)
        let m = model(scoreLogs: store, scoreLogId: log.id)

        // Drives the mutation through the same `for await` loop `MatchView`'s
        // `.task` runs, not `reload()` - this is the path that must actually
        // notice a match removed elsewhere.
        let collecting = Task { await m.start() }
        defer { collecting.cancel() }

        store.removeLocally(id: log.id)

        // `logs` is a StateFlow: it always conflates to its latest value, so this
        // is not racing the mutation against the loop's subscription - whichever
        // happens first, the loop's next delivered value already reflects the
        // removal. What it is waiting on is the Kotlin coroutine that bridges that
        // StateFlow into this Swift `for await` actually getting scheduled, which
        // this test does not control directly, hence the poll instead of a bare
        // assertion right after `removeLocally`.
        let deadline = Date().addingTimeInterval(5)
        while m.log != nil && Date() < deadline {
            try await Task.sleep(nanoseconds: 20_000_000)
        }

        XCTAssertNil(m.log)
        XCTAssertFalse(m.canAddVideo)
    }

    // MARK: - start() and the `.task` that runs it

    func testLeavingThePageStopsEveryLoopItStarted() async {
        // `MatchView`'s `.task` is cancelled when the page leaves the screen and
        // runs again on the way back. Three of start()'s four loops were
        // unstructured `Task {}`s, so cancelling stopped one loop and left three
        // running for good, and every return to the page added three more - each
        // re-folding the whole match on every emission of its flow. A loop that
        // never ends also never lets go of the model it captured, which is how
        // this sees them: the model must be released once the page has gone.
        let store = store()
        let log = newMatch(store)
        var m: MatchModel? = model(
            scoreLogs: store, scoreLogId: log.id,
            players: PlayersTestDoublesKt.testPlayersRepository(players: [])
        )
        weak var released = m

        let returned = await ObservationLifecycle.runAndCancel { [m] in await m?.start() }
        XCTAssertTrue(returned, "start() did not return after its task was cancelled")

        m = nil
        let gone = await ObservationLifecycle.wait { released == nil }
        XCTAssertTrue(gone, "a loop start() spawned is still running and still holds the model")
    }

    private func uploadedEntry(id: String, title: String) -> LocalVideoEntry {
        LocalVideoEntry(
            id: id, uri: "file://\(id)", displayName: "m.mp4",
            durationMs: 1000, sizeBytes: 10, addedAtEpochMs: 0,
            title: title, description: nil, keypoints: nil,
            stage: .analyzed, failedStep: nil, failureMessage: nil, resultSeen: false,
            scoreLogId: nil,
            homePlayerId: nil, awayPlayerId: nil, homePlayers: [], awayPlayers: [], nearSide: nil
        )
    }

    func testAVideoBornMatchSavesItsDetailsOntoTheVideoAndItsLocalEntry() async {
        // No score log at all: cloudia, uploaded from this phone and never scored.
        let videos = IosTestDoublesKt.testVideosRepository()
        let localVideos = IosTestDoublesKt.testLocalVideoRepository()
        localVideos.add(entry: uploadedEntry(id: "vid-1", title: "cloudia"))
        let m = model(scoreLogs: store(), scoreLogId: nil, localVideos: localVideos, videos: videos)

        await m.updateVideoDetails(
            videoId: "vid-1", title: "Club final", description: "", format: .doubles,
            players: MatchPlayers(
                homePlayerId: nil, awayPlayerId: nil, homePlayers: ["Coen", "Ana"], awayPlayers: ["Marco", "Li"]
            ),
            nearSide: nil
        )

        let written = videos.detailUpdates.first
        XCTAssertEqual(written?.title, "Club final")
        XCTAssertEqual(written?.homePlayers, ["Coen", "Ana"])
        XCTAssertEqual(written?.format, .doubles)
        XCTAssertEqual(localVideos.get(id: "vid-1")?.title, "Club final")
        XCTAssertNil(m.error)
    }

    func testARefusedDetailsSaveSurfacesAMessageAndLeavesTheLocalEntryAlone() async {
        let videos = IosTestDoublesKt.testVideosRepository()
        videos.failUpdateDetails = true
        let localVideos = IosTestDoublesKt.testLocalVideoRepository()
        localVideos.add(entry: uploadedEntry(id: "vid-1", title: "cloudia"))
        let m = model(scoreLogs: store(), scoreLogId: nil, localVideos: localVideos, videos: videos)

        await m.updateVideoDetails(
            videoId: "vid-1", title: "Club final", description: "", format: nil,
            players: MatchPlayers.companion.NONE, nearSide: nil
        )

        XCTAssertEqual(m.error, VideoMatchDetailsKt.VIDEO_DETAILS_SAVE_FAILED_MESSAGE)
        XCTAssertEqual(localVideos.get(id: "vid-1")?.title, "cloudia")
    }

    // MARK: - Who played

    /// Marco, a profile, against Li, a typed name: the assignment the editors
    /// hand over for a singles match.
    private let marcoVsLi = MatchPlayers(
        homePlayerId: "p-marco", awayPlayerId: nil, homePlayers: [], awayPlayers: ["Li"]
    )

    func testSavingPlayersOnAScoredMatchAssignsTheLog() async {
        // Title, format and players in the one write (Ruling 1).
        let store = store()
        let log = newMatch(store)
        let m = model(scoreLogs: store, scoreLogId: log.id)

        await m.updateDetails(title: "Club final", format: .singles, players: marcoVsLi, end: nil)

        let saved = store.get(id: log.id)
        XCTAssertEqual(saved?.title, "Club final")
        XCTAssertEqual(saved?.format, .singles)
        XCTAssertEqual(saved?.matchPlayers, marcoVsLi)
        XCTAssertNil(m.error)
    }

    func testAProfileOnADoublesScoreLogIsRefusedWithTheSentence() async {
        // The database refuses a profile on a doubles log, so the page says why
        // instead and writes nothing at all, the name included.
        let store = store()
        let log = newMatch(store)
        let m = model(scoreLogs: store, scoreLogId: log.id)

        await m.updateDetails(title: "Club final", format: .doubles, players: marcoVsLi, end: nil)

        XCTAssertEqual(m.error, PlayersCopy.shared.DOUBLES_NOTE)
        XCTAssertEqual(store.get(id: log.id)?.title, "Thu League")
        XCTAssertNil(store.get(id: log.id)?.homePlayerId)
    }

    func testAChangedEndOfAScoredMatchIsWrittenToItsVideoWithNobodyOnIt() async {
        // The log speaks for the players; its video, still on the phone, learns
        // only which end Home was at.
        let store = store()
        let log = newMatch(store)
        store.finish(id: log.id)
        let localVideos = IosTestDoublesKt.testLocalVideoRepository()
        localVideos.add(entry: entry(scoreLogId: log.id))
        let m = model(scoreLogs: store, scoreLogId: log.id, localVideos: localVideos)

        await m.updateDetails(
            title: "Thu League", format: nil, players: marcoVsLi,
            end: VideoEndChange(nearSide: .away, videoId: nil, entryId: "e1")
        )

        XCTAssertEqual(store.get(id: log.id)?.matchPlayers, marcoVsLi)
        XCTAssertEqual(localVideos.get(id: "e1")?.nearSide, .away)
        XCTAssertEqual(localVideos.get(id: "e1")?.matchPlayers, MatchPlayers.companion.NONE)
        XCTAssertNil(m.error)
    }

    func testSavingPlayersOnAPhoneOnlyMatchWritesTheEntry() async {
        let localVideos = IosTestDoublesKt.testLocalVideoRepository()
        localVideos.add(entry: LocalVideoEntry(
            id: "e1", uri: "content://x/e1", displayName: "m.mp4",
            durationMs: 1000, sizeBytes: 10, addedAtEpochMs: 0,
            title: nil, description: nil, keypoints: nil,
            stage: .local, failedStep: nil, failureMessage: nil, resultSeen: false,
            scoreLogId: nil,
            homePlayerId: nil, awayPlayerId: nil, homePlayers: [], awayPlayers: [], nearSide: nil
        ))
        let m = model(scoreLogs: store(), scoreLogId: nil, localVideos: localVideos)

        await m.saveEntryDetails(
            entryId: "e1", title: "Club final", description: "", players: marcoVsLi, nearSide: .home
        )

        XCTAssertEqual(localVideos.get(id: "e1")?.matchPlayers, marcoVsLi)
        XCTAssertEqual(localVideos.get(id: "e1")?.nearSide, .home)
        XCTAssertEqual(localVideos.get(id: "e1")?.title, "Club final")
        XCTAssertNil(m.error)
    }

    func testAVideoBornMatchSavesItsPlayersAndEndInTheOneDetailsWrite() async {
        let videos = IosTestDoublesKt.testVideosRepository()
        let localVideos = IosTestDoublesKt.testLocalVideoRepository()
        localVideos.add(entry: uploadedEntry(id: "vid-1", title: "cloudia"))
        let m = model(scoreLogs: store(), scoreLogId: nil, localVideos: localVideos, videos: videos)

        await m.updateVideoDetails(
            videoId: "vid-1", title: "cloudia", description: "", format: .singles,
            players: marcoVsLi, nearSide: .away
        )

        XCTAssertEqual(videos.detailUpdates.count, 1)
        XCTAssertEqual(videos.detailUpdates.first?.homePlayerId, "p-marco")
        XCTAssertEqual(videos.detailUpdates.first?.awayPlayers, ["Li"])
        XCTAssertEqual(videos.detailUpdates.first?.nearSide, .away)
        XCTAssertEqual(localVideos.get(id: "vid-1")?.nearSide, .away)
        XCTAssertNil(m.error)
    }

    func testComingBackToThePageFollowsTheStoreAgain() async throws {
        let store = store()
        let log = newMatch(store)
        store.finish(id: log.id)
        let videos = IosTestDoublesKt.testLocalVideoRepository()
        let m = model(scoreLogs: store, scoreLogId: log.id, localVideos: videos)

        let returned = await ObservationLifecycle.runAndCancel { await m.start() }
        XCTAssertTrue(returned, "start() did not return after its task was cancelled")
        XCTAssertTrue(m.canAddVideo)

        let again = Task { await m.start() }
        defer { again.cancel() }
        // Through the entries flow rather than the score log: `readStore` reads
        // the log directly on any emission, so only a change that arrives
        // THROUGH a loop proves that loop is running again.
        videos.add(entry: entry(scoreLogId: log.id))

        let noticed = await ObservationLifecycle.wait { !m.canAddVideo }
        XCTAssertTrue(noticed, "the second start() did not pick the entries flow back up")
    }
}

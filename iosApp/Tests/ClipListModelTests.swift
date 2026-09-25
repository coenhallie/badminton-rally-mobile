import Shared
import XCTest
@testable import iosApp

/// The match list's loops, held to what `ClipListView`'s `.task` does to them,
/// and its deletes, held to the row's own entry.
///
/// Built on a real `RallyApp`, because `ClipListModel` takes one and shared has
/// no doubles for the auth and shares repositories it reads. The loop tests
/// only observe local caches. The delete tests swap in doubles for every store
/// a delete writes; the one real call left is the shares lookup in the
/// `refresh()` a successful video delete runs, which goes to
/// `test.supabase.co`, a name that does not resolve, so it fails at once. It
/// is a soft failure the model ignores, and no assertion depends on it.
@MainActor
final class ClipListModelTests: XCTestCase {

    private var rally: RallyApp!
    private var entryId: String!

    override func setUp() {
        rally = RallyAppIosKt.createRallyApp(
            url: "https://test.supabase.co",
            anonKey: "test-anon-key",
            deleteLocalVideoFile: { _ in },
            deleteLocalAnalysis: { _, _ in }
        )
        // Unique, and removed again below: this registry is the simulator's real
        // NSUserDefaults, shared with every other test that builds a RallyApp.
        entryId = "clip-list-\(UUID().uuidString)"
    }

    override func tearDown() {
        rally.localVideos.remove(id: entryId)
    }

    private func model() -> ClipListModel {
        // The coordinator double, wired to this app's own stores: nothing here
        // runs the pipeline, and the real factory wants an upload channel.
        ClipListModel(
            rally: rally,
            analyze: IosTestDoublesKt.testAnalyzeCoordinator(localVideos: rally.localVideos, clips: rally.clips)
        )
    }

    private func entry() -> LocalVideoEntry {
        LocalVideoEntry(
            id: entryId, uri: "LocalVideos/\(entryId!).mp4", displayName: "m.mp4",
            durationMs: 1000, sizeBytes: 2, addedAtEpochMs: 1,
            title: nil, description: nil, keypoints: nil,
            stage: .local, failedStep: nil, failureMessage: nil, resultSeen: false,
            scoreLogId: nil
        )
    }

    func testComingBackToTheListStartsObservingAgain() async {
        // The reported defect. Opening a match cancels the list's `.task`, which
        // ended the one loop start() ran inline - the clips one. Coming back ran
        // start() again, and a `started` latch made that a no-op, so the list
        // never followed the clips flow again for the life of the view.
        let m = model()
        let returned = await ObservationLifecycle.runAndCancel { await m.observe() }
        XCTAssertTrue(returned, "observe() did not return after its task was cancelled")

        let stillRunning = ObservationLifecycle.Flag()
        let again = Task { @MainActor in
            await m.observe()
            stillRunning.set()
        }
        defer { again.cancel() }
        try? await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertFalse(stillRunning.isSet, "the second observe() returned at once instead of observing")

        rally.localVideos.add(entry: entry())
        let noticed = await ObservationLifecycle.wait { m.localEntries.contains { $0.id == self.entryId } }
        XCTAssertTrue(noticed, "the second observe() is not following the entries flow")
    }

    func testLeavingTheListStopsEveryLoopItStarted() async {
        // Three of the four loops were unstructured `Task {}`s that nothing ever
        // cancelled. See `MatchModelTests` for why a released model is the probe.
        var m: ClipListModel? = model()
        weak var released = m

        let returned = await ObservationLifecycle.runAndCancel { [m] in await m?.observe() }
        XCTAssertTrue(returned, "observe() did not return after its task was cancelled")

        m = nil
        let gone = await ObservationLifecycle.wait { released == nil }
        XCTAssertTrue(gone, "a loop observe() spawned is still running and still holds the model")
    }

    func testRefreshIncrementsTheGenerationEveryTime() async {
        // `MatchThumbnail.CoverRetryKey` reads this to decide whether a failed
        // cover clip deserves another try. It has to move on every completed
        // pull, not just the ones that changed something, since a coach who
        // was offline for one failed fetch and is back online for the next
        // pull sees the exact same clips either way.
        let m = model()
        XCTAssertEqual(m.refreshGeneration, 0)
        await m.refresh()
        XCTAssertEqual(m.refreshGeneration, 1)
        await m.refresh()
        XCTAssertEqual(m.refreshGeneration, 2)
    }

    // MARK: - Deletes act on the row's own entry

    // 2026-08-28T18:00:00Z. Fixed, because the store stamps created_at from it.
    private let t0 = KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: 1_787_940_000_000)

    /// A model over doubles for every store a delete touches. The local-only
    /// score log store refuses every server delete, which is exactly what
    /// "the server said no" needs; `RecordingVideosRepository` can say either.
    private struct Rig {
        let model: ClipListModel
        let localVideos: LocalVideoRepository
        let annotations: LocalAnnotationsRepository
        let videos: RecordingVideosRepository
        let scoreLogs: ScoreLogsRepository
    }

    /// `liveDeviceRuns` defaults to "nothing running", matching a build with no
    /// on-device runner. Tests that need a run in flight pass their own.
    private func rig(liveDeviceRuns: @escaping @MainActor () -> [String: DeviceRun] = { [:] }) -> Rig {
        let localVideos = IosTestDoublesKt.testLocalVideoRepository()
        let annotations = IosTestDoublesKt.testLocalAnnotationsRepository()
        let videos = IosTestDoublesKt.testVideosRepository()
        let scoreLogs = SwiftInteropKt.testScoreLogsRepository(now: t0, ownerId: "owner-1")
        let clips = IosTestDoublesKt.testClipsRepository()
        let model = ClipListModel(
            rally: rally,
            analyze: IosTestDoublesKt.testAnalyzeCoordinator(localVideos: localVideos, clips: clips),
            clips: clips, videos: videos, scoreLogs: scoreLogs,
            localVideos: localVideos, localAnnotations: annotations,
            liveDeviceRuns: liveDeviceRuns
        )
        return Rig(model: model, localVideos: localVideos, annotations: annotations, videos: videos, scoreLogs: scoreLogs)
    }

    private func analyzedEntry(id: String, scoreLogId: String? = nil) -> LocalVideoEntry {
        LocalVideoEntry(
            id: id, uri: "LocalVideos/\(id).mp4", displayName: "m.mp4",
            durationMs: 1000, sizeBytes: 2, addedAtEpochMs: 1,
            title: nil, description: nil, keypoints: nil,
            stage: .analyzed, failedStep: nil, failureMessage: nil, resultSeen: false,
            scoreLogId: scoreLogId
        )
    }

    private func note(on videoId: String, _ r: Rig) {
        _ = r.annotations.add(videoId: videoId, timestampSeconds: 1, body: "n", label: nil)
    }

    func testDeletingAMatchEverywhereTheServerAcceptsDiscardsTheFileToo() async {
        // Review focus 4: the surviving ANALYZED entry would bring the row
        // straight back as a phone-only match.
        let r = rig()
        r.localVideos.add(entry: analyzedEntry(id: "v1"))
        note(on: "v1", r)

        await r.model.deleteMatch(videoId: "v1", entryId: "v1")

        XCTAssertEqual(r.videos.deletedVideoIds, ["v1"])
        XCTAssertNil(r.localVideos.get(id: "v1"))
        XCTAssertFalse(r.annotations.hasAnnotations(videoId: "v1"))
    }

    func testDeletingAMatchTheServerRefusesKeepsTheFile() async {
        let r = rig()
        r.videos.failDeleteMatch = true
        r.localVideos.add(entry: analyzedEntry(id: "v1"))
        note(on: "v1", r)

        await r.model.deleteMatch(videoId: "v1", entryId: "v1")

        XCTAssertNotNil(r.localVideos.get(id: "v1"), "a refused delete threw the file away under a surviving match")
        XCTAssertTrue(r.annotations.hasAnnotations(videoId: "v1"))
        XCTAssertNotNil(r.model.error)
    }

    func testAScoredMatchDeleteDiscardsTheEntryItOrphansWhateverTheServerSays() async {
        // The log leaves this phone win or lose, so an entry naming it would be
        // invisible and unremovable if it stayed. The local-only store refuses.
        let r = rig()
        let log = r.scoreLogs.create(title: "Thu", rules: ScoringRules.companion.BWF_21, setup: MatchSetup(firstServer: .home))
        r.localVideos.add(entry: analyzedEntry(id: "e1", scoreLogId: log.id))

        await r.model.deleteScoreMatch(scoreLogId: log.id, entryId: "e1")

        XCTAssertNil(r.localVideos.get(id: "e1"))
        XCTAssertNotNil(r.model.error)
    }

    func testABoundDeleteTheServerRefusesKeepsAnEntryJoinedOnlyByItsId() async {
        // The list joins this entry to the log through log.videoId; the entry
        // does not name the log, so the log's removal does not orphan it, and
        // the match may yet survive in the cloud.
        let r = rig()
        let log = r.scoreLogs.create(title: "Thu", rules: ScoringRules.companion.BWF_21, setup: MatchSetup(firstServer: .home))
        r.scoreLogs.attachVideo(id: log.id, videoId: "v1")
        r.localVideos.add(entry: analyzedEntry(id: "v1"))

        await r.model.deleteBoundMatch(videoId: "v1", scoreLogId: log.id, entryId: "v1")

        XCTAssertNotNil(r.localVideos.get(id: "v1"))
        XCTAssertEqual(r.videos.deletedVideoIds, [], "the video delete ran after the score log's was refused")
    }

    func testAnEntryARunIsHoldingIsNeverDiscarded() async {
        // The guard reads the injected `liveDeviceRuns` closure directly - the
        // stand-in for `LocalAnalysisRunner.deviceRuns` - and NOT
        // `updateDeviceRuns`'s own cache, which this test deliberately never
        // calls. `updateDeviceRuns` only feeds `matchList`; before this fix a
        // run started on a screen that had not yet fed the model this way
        // would not block a delete.
        let r = rig(liveDeviceRuns: { ["v1": DeviceRun(kind: .running, phase: .analysing, message: nil)] })
        r.localVideos.add(entry: analyzedEntry(id: "v1"))

        await r.model.deleteMatch(videoId: "v1", entryId: "v1")

        XCTAssertNotNil(r.localVideos.get(id: "v1"))
    }

    func testAPausedRunIsAlsoNeverDiscarded() async {
        let r = rig(liveDeviceRuns: { ["v1": DeviceRun(kind: .paused, phase: .analysing, message: "Paused - keep Shuttl open")] })
        r.localVideos.add(entry: analyzedEntry(id: "v1"))

        await r.model.deleteMatch(videoId: "v1", entryId: "v1")

        XCTAssertNotNil(r.localVideos.get(id: "v1"))
    }

    func testACachedDeviceRunWithNoLiveRunnerDoesNotBlockADelete() async {
        // `updateDeviceRuns` alone - the old, sole guard - must no longer stop
        // this delete: the runner itself (via `liveDeviceRuns`, defaulted here
        // to "nothing running") is what `discardEntry` consults now.
        let r = rig()
        r.localVideos.add(entry: analyzedEntry(id: "v1"))
        r.model.updateDeviceRuns(["v1": DeviceRun(kind: .running, phase: .analysing, message: nil)])

        await r.model.deleteMatch(videoId: "v1", entryId: "v1")

        XCTAssertNil(r.localVideos.get(id: "v1"))
    }

    func testRemovingFromThePhoneFreesTheFileAndItsNotes() {
        let r = rig()
        r.localVideos.add(entry: analyzedEntry(id: "v1"))
        note(on: "v1", r)

        r.model.removeFromPhone(entryId: "v1")

        XCTAssertNil(r.localVideos.get(id: "v1"))
        XCTAssertFalse(r.annotations.hasAnnotations(videoId: "v1"))
    }
}

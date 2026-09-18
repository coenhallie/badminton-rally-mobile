import Shared
import XCTest
@testable import iosApp

/// What `AnalyticsDetailView` composes, driven through the same pieces in the
/// same order. The port of androidApp's `CloudAnalysisPanelsTest`, case for case
/// and name for name.
///
/// The Base tab's absence for a cloud analysis was a WIRING defect, not a logic
/// one: every part worked and the screen fed `hasBoundedClips` from the device
/// clip index, which a cloud run never writes. So the parts are assembled here
/// rather than asserted one at a time, over the real store.
///
/// Two assumptions this pins that nothing else does. That `PlayerTrackStore.load`
/// stamps the source it read from, without which the screen cannot tell a cloud
/// track from a local one and pairs the wrong windows to it. And that a clip's
/// `videoId` is the entry id the stores are keyed by - true because
/// `AnalyzeCoordinator` creates the videos row with `entry.id`, and the whole
/// class of bug this branch fixed was an id that used to be one thing being
/// quietly asked of another.
final class CloudAnalysisPanelsTests: XCTestCase {

    private let entryId = "cloud-panels-under-test"

    override func setUpWithError() throws {
        AnalysisFiles.deleteAll(entryId: entryId, reason: .discarded)
    }

    override func tearDownWithError() throws {
        AnalysisFiles.deleteAll(entryId: entryId, reason: .discarded)
    }

    private func selection(_ side: CourtSide, samples: Int) -> PlayerSelection {
        PlayerSelection(
            side: side,
            track: PlayerTrack(
                samples: (0..<samples).map {
                    PlayerSample(frame: Int32($0), courtPosition: Point(x: Double($0) * 0.1, y: 3.05))
                },
                framesWithPose: Int32(samples),
                rejections: [:]
            ),
            poses: []
        )
    }

    private func clip(_ index: Int32, _ start: Float, _ end: Float, videoId: String? = nil) -> RallyClip {
        RallyClip(
            id: "c-\(index)",
            videoId: videoId ?? entryId,
            ownerId: "user-self",
            rallyIndex: index,
            startTimestamp: start,
            endTimestamp: end,
            durationSeconds: end - start,
            clipStoragePath: "user-self/\(entryId)/rally_\(index).mp4",
            thumbnailStoragePath: nil,
            title: nil,
            annotationCount: 0,
            createdAt: KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: 0)
        )
    }

    /// Exactly what `measureMovement` -> `saveCloudAnalysis` leaves on disk.
    @discardableResult
    private func installCloudAnalysis() throws -> PlayerTrackStore {
        let store = PlayerTrackStore()
        try store.saveAll(
            entryId: entryId,
            source: .cloud,
            selections: [selection(.near, samples: 4), selection(.far, samples: 3)],
            fps: 30.0
        )
        return store
    }

    /// The panel set the detail screen builds, from the inputs it builds them from.
    private func panelsFor(
        _ store: PlayerTrackStore,
        cloudClips: [RallyClip],
        hasSkeleton: Bool = false,
        hasVideo: Bool = false
    ) -> [AnalyticsPanel] {
        let stored = store.load(entryId: entryId, source: .cloud)
            ?? store.load(entryId: entryId, source: .local)
        let windows = CloudRallyWindowsKt.rallyWindowsFor(
            preferCloud: stored?.source == .cloud,
            cloud: CloudRallyWindowsKt.cloudRallyWindows(clips: cloudClips, videoId: entryId),
            device: rallyWindows(done: [], stored: store.loadClips(entryId: entryId))
        )
        return AnalyticsPanelKt.availablePanels(
            hasTrack: stored != nil,
            hasBoundedClips: windows.contains { $0.isBounded },
            hasSkeleton: hasSkeleton,
            hasVideo: hasVideo
        )
    }

    func testACloudAnalysisOffersTheHeatmapAndTheBasePosition() throws {
        // The headline: a coach who chose "analyze in the cloud" gets the same
        // two panels a device run gives, on a phone that cut no clips itself.
        let store = try installCloudAnalysis()

        XCTAssertEqual(
            panelsFor(store, cloudClips: [clip(1, 4, 21.5), clip(2, 30, 44)]),
            [.heatmap, .base]
        )
    }

    func testTheCloudTrackIsReadBackAsACloudTrack() throws {
        // Everything above hangs off this. If load() did not stamp the source,
        // the screen would pair the device's windows to the cloud's track and
        // the Base tab would go missing again - silently, since both are lists
        // of rallies.
        let store = try installCloudAnalysis()
        let stored = try XCTUnwrap(store.load(entryId: entryId, source: .cloud))

        XCTAssertEqual(stored.source, .cloud)
        XCTAssertEqual(stored.tracks.map(\.side), [.near, .far])
    }

    func testTheBaseTabWaitsForTheClipCacheRatherThanBorrowing() throws {
        // The beat between the screen opening and rally_clips landing. The tab
        // is not offered yet; it must not appear measured over some other run's
        // boundaries.
        let store = try installCloudAnalysis()

        XCTAssertEqual(panelsFor(store, cloudClips: []), [.heatmap])
    }

    func testAnotherVideosClipsDoNotFurnishThisOnesBaseTab() throws {
        // The filter in cloudRallyWindows, reached the way the screen reaches
        // it: the cache holds every clip the coach has.
        let store = try installCloudAnalysis()

        XCTAssertEqual(
            panelsFor(store, cloudClips: [clip(1, 0, 9, videoId: "some-other-video")]),
            [.heatmap]
        )
    }

    func testTheSkeletonJoinsThemOnlyWhileTheVideoIsStillHere() throws {
        // The one place parity stops, and it is honest: a cloud run releases
        // the footage, and the skeleton overlays playback. An annotated video
        // is kept as ANALYZED, and then all three are offered.
        let store = try installCloudAnalysis()
        let clips = [clip(1, 4, 21.5)]

        XCTAssertEqual(
            panelsFor(store, cloudClips: clips, hasSkeleton: true, hasVideo: false),
            [.heatmap, .base]
        )
        XCTAssertEqual(
            panelsFor(store, cloudClips: clips, hasSkeleton: true, hasVideo: true),
            [.heatmap, .base, .skeleton]
        )
    }

    @MainActor
    func testTheListSeesACloudOnlyTrack() throws {
        // The match list asks the runner `storedTrackIds` for the whole list at
        // once, and that asked the store's `has` with its default source -
        // `.local`. A cloud run writes only under the cloud subtree, so a
        // finished cloud analysis read as never analysed on the list while the
        // detail screen, which asks `hasStoredTrack`, could open it.
        try installCloudAnalysis()
        let runner = LocalAnalysisRunner()

        XCTAssertTrue(runner.hasStoredTrack(entryId: entryId))
        XCTAssertEqual(
            runner.storedTrackIds(among: [entryId, "never-analysed"]),
            [entryId],
            "the list and the detail screen disagree about whether this entry has a track"
        )
    }
}

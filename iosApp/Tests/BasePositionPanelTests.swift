import Shared
import XCTest
@testable import iosApp

/// The two decisions the base panel makes before it draws anything: which set of
/// clips the rally windows come from, and which of the four things it can say
/// when there is nothing to draw.
///
/// The measurement itself is Kotlin - `basePositions` and its medians, and
/// `describeBase`'s wording - and covered in `commonTest` against both platforms.
/// What is tested here is the Swift that chooses what to hand it, which is where
/// a port of this shape goes wrong.
final class BasePositionPanelTests: XCTestCase {

    private func clip(_ index: Int, _ start: Double, _ end: Double) -> PlayerTrackStore.Clip {
        PlayerTrackStore.Clip(
            index: index,
            url: URL(fileURLWithPath: "/tmp/rally-\(index).mp4"),
            startSeconds: start,
            endSeconds: end
        )
    }

    private func track(samples: Int, framesWithPose: Int? = nil) -> PlayerTrack {
        PlayerTrack(
            samples: (0..<samples).map {
                PlayerSample(frame: Int32($0), courtPosition: Point(x: 3.05, y: 9.0))
            },
            framesWithPose: Int32(framesWithPose ?? samples),
            rejections: [:]
        )
    }

    // MARK: - Which clips the windows come from

    func testARunStillInMemoryWinsOverTheSidecar() {
        let windows = rallyWindows(
            done: [clip(1, 0, 5)],
            stored: [clip(1, 100, 105), clip(2, 110, 115)]
        )
        XCTAssertEqual(windows.map(\.index), [1])
        XCTAssertEqual(windows.first?.startSeconds, 0)
    }

    func testAPoseOnlyRunFallsThroughToTheStoredClips() {
        // A run asked for pose alone cuts nothing. Preferring it would drop the
        // windows a previous run left behind.
        let windows = rallyWindows(done: [], stored: [clip(1, 100, 105), clip(2, 110, 115)])
        XCTAssertEqual(windows.map(\.index), [1, 2])
        XCTAssertEqual(windows.map(\.isBounded), [true, true])
    }

    func testClipsRecoveredByFilenameReachTheMeasurementUnbounded() {
        // `scanClips` leaves bounds at zero rather than fabricating them, and an
        // unbounded window is one `basePositions` skips.
        let windows = rallyWindows(done: [], stored: [clip(3, 0, 0)])
        XCTAssertEqual(windows.map(\.index), [3])
        XCTAssertEqual(windows.map(\.isBounded), [false])
    }

    // MARK: - What it says when it cannot draw

    func testAnUnloadedAnalysisSaysSoRatherThanDrawingAnEmptyCourt() {
        XCTAssertEqual(
            baseMessage(track: nil, windows: [], bases: nil, measured: true),
            "This analysis is no longer loaded. Run it again to see where the player stood."
        )
    }

    func testAPoseLessRunIsExplainedByTheHeatmapsOwnWording() {
        // One explanation of an empty track across both panels, so the two tabs
        // cannot give the coach different reasons for the same run.
        let empty = PlayerTrack(samples: [], framesWithPose: 0, rejections: [:])
        let source = empty
        XCTAssertEqual(
            baseMessage(track: source, windows: [], bases: nil, measured: true),
            whyEmpty(empty)
        )
    }

    func testATrackWithNoBoundedWindowsSaysTheWindowsWereNotKept() {
        let source = track(samples: 100)
        let windows = rallyWindows(done: [], stored: [clip(1, 0, 0)])
        XCTAssertEqual(
            baseMessage(track: source, windows: windows, bases: nil, measured: true),
            "The rally windows for this analysis were not kept, so there is nothing to measure "
                + "per rally. Run the analysis again."
        )
    }

    func testARallyTooThinToMeasureIsReportedRatherThanDrawnEmpty() {
        // One second of samples at 30fps is the floor; a rally under it counts
        // for nothing and leaves `rallies` empty.
        let source = track(samples: 5)
        let windows = rallyWindows(done: [], stored: [clip(1, 0, 5)])
        let bases = BasePositionKt.basePositions(
            track: source, fps: 30, windows: windows,
            minSeconds: BasePositionKt.MIN_RALLY_SECONDS
        )
        XCTAssertTrue(bases.rallies.isEmpty)
        XCTAssertEqual(
            baseMessage(track: source, windows: windows, bases: bases, measured: true),
            "The player was not found for long enough in any rally to say where they stood."
        )
    }

    func testTheLastRungWaitsForTheMeasurementRatherThanFlashing() {
        // Before `basePositions` has run, a panel that said "not found" would be
        // making a claim about the match out of a claim about the frame.
        let source = track(samples: 100)
        let windows = rallyWindows(done: [], stored: [clip(1, 0, 5)])
        XCTAssertNil(baseMessage(track: source, windows: windows, bases: nil, measured: false))
    }

    func testAMeasuredRallyDrawsRatherThanExplains() {
        let source = track(samples: 200)
        let windows = rallyWindows(done: [], stored: [clip(1, 0, 6)])
        let bases = BasePositionKt.basePositions(
            track: source, fps: 30, windows: windows,
            minSeconds: BasePositionKt.MIN_RALLY_SECONDS
        )
        XCTAssertEqual(bases.rallies.map(\.index), [1])
        XCTAssertNil(baseMessage(track: source, windows: windows, bases: bases, measured: true))
        // The court position is the one the samples were seeded at, said the way
        // a coach reads it: y 9.0 is 0.32 m behind the near service line at
        // 8.68, and x 3.05 is the centre line itself.
        XCTAssertEqual(
            BasePositionFormatKt.describeRally(base: bases.rallies[0]),
            "Rally 1 · 0.32 m behind the service line, on the centre line · found in 100% of frames"
        )
    }

    // MARK: - Which tracks the heatmap draws (Task 14)
    //
    // The port of Android's HeatmapSourceTest, case for case and name for name,
    // so the two can be read side by side. Two platforms disagreeing about which
    // player the court shows is what a shared rule cannot prevent here: the
    // resolution is Swift on one side and Kotlin on the other.

    private func trackWith(samples: Int) -> PlayerTrack {
        PlayerTrack(
            samples: (0..<samples).map {
                PlayerSample(frame: Int32($0), courtPosition: Point(x: Double($0) * 0.1, y: 3.05))
            },
            framesWithPose: Int32(samples),
            rejections: [:]
        )
    }

    private func stored(_ pairs: [(CourtSide, Int)], fps: Double = 30.0) -> PlayerTrackStore.Stored {
        PlayerTrackStore.Stored(
            tracks: pairs.map {
                PlayerTrackStore.SideTrack(side: $0.0, track: trackWith(samples: $0.1))
            },
            fps: fps
        )
    }

    private func done(samples: Int, fps: Double = 25.0) -> LocalAnalysisState.Done {
        LocalAnalysisState.Done(
            rallies: 2,
            shuttleVisible: 50,
            totalFrames: 100,
            clips: [],
            elapsedSeconds: 9.0,
            playerTrack: trackWith(samples: samples),
            fps: fps
        )
    }

    func testAStoredPairOfTracksIsOfferedAsAPair() throws {
        let source = try XCTUnwrap(heatmapSource(done: nil, stored: stored([(.near, 3), (.far, 2)])))

        XCTAssertEqual(source.tracks.map(\.side), [.near, .far])
        XCTAssertEqual(source.fps, 30.0)
    }

    func testAnInMemoryRunIsStillOneNearTrack() throws {
        // A device run produces the near player and nothing else, so the
        // in-memory branch cannot grow a second track and the toggle will not
        // appear for it. Pinned because a reader of the pair type above would
        // reasonably assume otherwise.
        let source = try XCTUnwrap(heatmapSource(done: done(samples: 4), stored: nil))

        XCTAssertEqual(source.tracks.map(\.side), [.near])
    }

    func testAnEmptyInMemoryRunStillFallsThroughToAStoredPair() throws {
        // Unchanged behaviour, restated against the new shape. A run that asked
        // for no pose metric completes with an EMPTY PlayerTrack, and preferring
        // it blindly replaced a perfectly good stored heatmap with "No pose data
        // for this video". Court marking seeds its metrics to rally clips alone,
        // so a pose-less run is the DEFAULT.
        let source = try XCTUnwrap(
            heatmapSource(done: done(samples: 0), stored: stored([(.near, 3), (.far, 2)]))
        )

        XCTAssertEqual(source.tracks.count, 2)
        // The stored fps, not the run's: the frame rate belongs to the tracks it
        // was measured with.
        XCTAssertEqual(source.fps, 30.0)
    }

    func testASideWithNoSamplesIsNotOfferedAsAChoice() throws {
        // A toggle whose second option draws an empty court is a control that
        // cannot usefully be actuated, which is the same objection the tab row
        // was gated on. One usable track means no toggle.
        let source = try XCTUnwrap(heatmapSource(done: nil, stored: stored([(.near, 3), (.far, 0)])))

        XCTAssertEqual(source.tracks.map(\.side), [.near])
    }

    func testNeitherInMemoryNorStoredIsStillNothing() {
        XCTAssertNil(heatmapSource(done: nil, stored: nil))
    }

    func testAStoredPairWithNobodyOnEitherSideDrawsNoCourt() {
        // The filter can empty the list, and an empty HeatmapSource would be a
        // panel drawing a court with no heat on it rather than saying so.
        XCTAssertNil(heatmapSource(done: nil, stored: stored([(.near, 0), (.far, 0)])))
    }
}

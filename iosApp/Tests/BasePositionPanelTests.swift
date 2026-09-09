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
            baseMessage(source: nil, windows: [], bases: nil, measured: true),
            "This analysis is no longer loaded. Run it again to see where the player stood."
        )
    }

    func testAPoseLessRunIsExplainedByTheHeatmapsOwnWording() {
        // One explanation of an empty track across both panels, so the two tabs
        // cannot give the coach different reasons for the same run.
        let empty = PlayerTrack(samples: [], framesWithPose: 0, rejections: [:])
        let source = HeatmapSource(track: empty, fps: 30)
        XCTAssertEqual(
            baseMessage(source: source, windows: [], bases: nil, measured: true),
            whyEmpty(empty)
        )
    }

    func testATrackWithNoBoundedWindowsSaysTheWindowsWereNotKept() {
        let source = HeatmapSource(track: track(samples: 100), fps: 30)
        let windows = rallyWindows(done: [], stored: [clip(1, 0, 0)])
        XCTAssertEqual(
            baseMessage(source: source, windows: windows, bases: nil, measured: true),
            "The rally windows for this analysis were not kept, so there is nothing to measure "
                + "per rally. Run the analysis again."
        )
    }

    func testARallyTooThinToMeasureIsReportedRatherThanDrawnEmpty() {
        // One second of samples at 30fps is the floor; a rally under it counts
        // for nothing and leaves `rallies` empty.
        let source = HeatmapSource(track: track(samples: 5), fps: 30)
        let windows = rallyWindows(done: [], stored: [clip(1, 0, 5)])
        let bases = BasePositionKt.basePositions(
            track: source.track, fps: source.fps, windows: windows,
            minSeconds: BasePositionKt.MIN_RALLY_SECONDS
        )
        XCTAssertTrue(bases.rallies.isEmpty)
        XCTAssertEqual(
            baseMessage(source: source, windows: windows, bases: bases, measured: true),
            "The player was not found for long enough in any rally to say where they stood."
        )
    }

    func testTheLastRungWaitsForTheMeasurementRatherThanFlashing() {
        // Before `basePositions` has run, a panel that said "not found" would be
        // making a claim about the match out of a claim about the frame.
        let source = HeatmapSource(track: track(samples: 100), fps: 30)
        let windows = rallyWindows(done: [], stored: [clip(1, 0, 5)])
        XCTAssertNil(baseMessage(source: source, windows: windows, bases: nil, measured: false))
    }

    func testAMeasuredRallyDrawsRatherThanExplains() {
        let source = HeatmapSource(track: track(samples: 200), fps: 30)
        let windows = rallyWindows(done: [], stored: [clip(1, 0, 6)])
        let bases = BasePositionKt.basePositions(
            track: source.track, fps: source.fps, windows: windows,
            minSeconds: BasePositionKt.MIN_RALLY_SECONDS
        )
        XCTAssertEqual(bases.rallies.map(\.index), [1])
        XCTAssertNil(baseMessage(source: source, windows: windows, bases: bases, measured: true))
        // The court position is the one the samples were seeded at, said the way
        // a coach reads it: y 9.0 is 0.32 m behind the near service line at
        // 8.68, and x 3.05 is the centre line itself.
        XCTAssertEqual(
            BasePositionFormatKt.describeRally(base: bases.rallies[0]),
            "Rally 1 · 0.32 m behind the service line, on the centre line · found in 100% of frames"
        )
    }
}

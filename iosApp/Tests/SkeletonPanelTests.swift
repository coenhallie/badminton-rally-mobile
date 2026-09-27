import Shared
import XCTest
@testable import iosApp

/// The two decisions the skeleton panel makes before it draws anything: what the
/// selected tile points at on the figure, and how a stored file resolves into a
/// court fit, a series and a player.
///
/// Everything either of them is measured with is Kotlin - `MetricKind`'s joint
/// lists, `poseMetrics`, `visibleKinds` - and covered in `commonTest`. What is
/// tested here is the Swift that unwraps it, which is exactly where a port of
/// this shape goes wrong.
final class SkeletonPanelTests: XCTestCase {

    private let entry = "skeleton-panel-under-test"

    override func setUpWithError() throws {
        AnalysisFiles.deleteAll(entryId: entry, reason: .discarded)
    }

    override func tearDownWithError() throws {
        AnalysisFiles.deleteAll(entryId: entry, reason: .discarded)
    }

    // MARK: - The highlight

    func testAnAngleKindPointsAtItsOwnThreeJoints() {
        // Read out of MetricKind rather than restated, so a kind whose arc moves
        // moves here too.
        XCTAssertEqual(
            poseHighlight(for: .elbowRight),
            .angle(
                a: Int(Coco.shared.RIGHT_SHOULDER),
                vertex: Int(Coco.shared.RIGHT_ELBOW),
                c: Int(Coco.shared.RIGHT_WRIST)
            )
        )
        XCTAssertEqual(
            poseHighlight(for: .kneeLeft),
            .angle(
                a: Int(Coco.shared.LEFT_HIP),
                vertex: Int(Coco.shared.LEFT_KNEE),
                c: Int(Coco.shared.LEFT_ANKLE)
            )
        )
    }

    func testATiltKindPointsAtItsLineAndStanceAtTheAnkles() {
        XCTAssertEqual(
            poseHighlight(for: .shoulders),
            .tilt(left: Int(Coco.shared.LEFT_SHOULDER), right: Int(Coco.shared.RIGHT_SHOULDER))
        )
        XCTAssertEqual(
            poseHighlight(for: .hips),
            .tilt(left: Int(Coco.shared.LEFT_HIP), right: Int(Coco.shared.RIGHT_HIP))
        )
        XCTAssertEqual(poseHighlight(for: .stance), .stance)
    }

    func testAKindWithNoGeometryHighlightsNothing() {
        // Behind the service line is a distance from a line that is not drawn,
        // and the lean is measured from a vertical that is not either. Drawing
        // something anyway would put a mark on the figure that the number does
        // not come from.
        XCTAssertNil(poseHighlight(for: .behindLine))
        XCTAssertNil(poseHighlight(for: .lean))
    }

    // MARK: - Loading

    @MainActor
    func testAnEntryWithNoStoredSkeletonLoadsAsAbsentRatherThanEmpty() async {
        let model = SkeletonPanelModel(
            entryId: entry, runner: LocalAnalysisRunner(), racketArmPrefs: prefs()
        )
        await model.loadSkeleton(videoRelativePath: nil)

        guard case .loaded(let loaded) = model.load else { return XCTFail("still loading") }
        // Absent, not an empty pose list: the panel's two lines say different
        // things, and "no skeleton was kept" is the one that tells a coach to
        // re-run with the metric ticked.
        XCTAssertNil(loaded.stored)
        XCTAssertTrue(loaded.seriesBySide.isEmpty)
        XCTAssertNil(model.player)
    }

    @MainActor
    func testMarksThatDoNotFitLoadAsBadRatherThanAsMetres() async throws {
        // The fixture's marks are the store test's: twelve points on a line,
        // which no homography fits. A court fit taken anyway would put a stance
        // in metres that is metres wrong, so it is refused and the two
        // court-plane tiles stay off the strip.
        try SkeletonStore().save(
            entryId: entry, poses: poses(4), fps: 30,
            videoWidth: 1920, videoHeight: 1080, marks: unfittableMarks()
        )
        let model = SkeletonPanelModel(
            entryId: entry, runner: LocalAnalysisRunner(), racketArmPrefs: prefs()
        )
        await model.loadSkeleton(videoRelativePath: nil)

        guard case .loaded(let loaded) = model.load else { return XCTFail("still loading") }
        XCTAssertEqual(loaded.courtFit, .bad)
        XCTAssertNil(loaded.homography)
        XCTAssertFalse(model.hasCourt)
        // One entry per pose either way: the angles are measurable without a
        // court, and only the two court-plane fields go null.
        // Keyed by side now. A v2 file is one near track, so there is exactly
        // one entry and it is the near one.
        XCTAssertEqual(loaded.seriesBySide.keys.map(\.self), [.near])
        XCTAssertEqual(model.shownSeries.count, 4)
        XCTAssertNil(model.shownSeries[0].metrics.stanceM)
    }

    @MainActor
    func testAFileWithNoMarksIsToldApartFromMarksThatDoNotFit() async throws {
        // Different problems with different fixes - a re-run for marks that were
        // never stored, a re-marking for marks that do not fit - so the panel
        // must be able to say which.
        try SkeletonStore().save(
            entryId: entry, poses: poses(2), fps: 30,
            videoWidth: 640, videoHeight: 360, marks: nil
        )
        let model = SkeletonPanelModel(
            entryId: entry, runner: LocalAnalysisRunner(), racketArmPrefs: prefs()
        )
        await model.loadSkeleton(videoRelativePath: nil)

        guard case .loaded(let loaded) = model.load else { return XCTFail("still loading") }
        XCTAssertEqual(loaded.courtFit, .none)
    }

    @MainActor
    func testTheSkeletonHasNoVideoWhenTheFileItWasMeasuredOnIsGone() async throws {
        try SkeletonStore().save(
            entryId: entry, poses: poses(2), fps: 30,
            videoWidth: 640, videoHeight: 360, marks: nil
        )
        let model = SkeletonPanelModel(
            entryId: entry, runner: LocalAnalysisRunner(), racketArmPrefs: prefs()
        )
        // A path that resolves to nothing: the entry's copy has been removed
        // while the skeleton it produced is still on disk.
        await model.loadSkeleton(videoRelativePath: "LocalVideos/does-not-exist.mp4")

        XCTAssertNil(model.player)
    }

    // MARK: - Selection

    @MainActor
    func testTheChoiceIsKeptWhileTheRacketArmHidesItsTile() async throws {
        try SkeletonStore().save(
            entryId: entry, poses: poses(2), fps: 30,
            videoWidth: 640, videoHeight: 360, marks: nil
        )
        let model = SkeletonPanelModel(
            entryId: entry, runner: LocalAnalysisRunner(), racketArmPrefs: prefs()
        )
        await model.loadSkeleton(videoRelativePath: nil)

        model.chosen = .elbowLeft
        XCTAssertEqual(model.selected, .elbowLeft)

        // Choosing the right arm takes the left elbow's tile off the strip, and
        // the graph and the arc must never show a kind with no tile.
        model.setRacketArm(.right)
        XCTAssertNotEqual(model.selected, .elbowLeft)
        XCTAssertTrue(
            MetricsFormatKt.visibleKinds(hasCourt: false, racketArm: .right).contains(model.selected)
        )

        // Putting the arm back brings the tile back selected: the choice was
        // kept rather than written over.
        model.setRacketArm(nil)
        XCTAssertEqual(model.selected, .elbowLeft)
    }

    // MARK: - The camera

    @MainActor
    func testEachPoseIsMeasuredThroughItsOwnFramesCourtFit() async throws {
        // The camera panned by (30, -12) between the marking frame and frame
        // 0, and was back by frame 1: the same stance, seen at two places in
        // the image. Measured through the marking frame's fit alone, frame 0
        // would read the pan as footwork.
        let panned = standing(frame: 0, dx: 30, dy: -12)
        let still = standing(frame: 1, dx: 0, dy: 0)
        try SkeletonStore().save(
            entryId: entry, poses: [panned, still], fps: 30,
            videoWidth: 1920, videoHeight: 1080, marks: fittingMarks()
        )
        let model = SkeletonPanelModel(
            entryId: entry, runner: LocalAnalysisRunner(), racketArmPrefs: prefs()
        )
        await model.loadSkeleton(videoRelativePath: nil)

        guard case .loaded(let loaded) = model.load else { return XCTFail("still loading") }
        XCTAssertEqual(loaded.courtFit, .ok)
        let expected = PoseMetricsKt.poseMetrics(
            keypoints: still.keypoints, confidence: still.confidence,
            homography: loaded.homography,
            minConfidence: NearPlayerSelector.companion.MIN_KEYPOINT_CONFIDENCE
        )
        let stance = try XCTUnwrap(expected.stanceM?.doubleValue)
        let behind = try XCTUnwrap(expected.behindServiceLineM?.doubleValue)

        // The series, one sample per pose.
        XCTAssertEqual(model.shownSeries.count, 2)
        for sample in model.shownSeries {
            XCTAssertEqual(try XCTUnwrap(sample.metrics.stanceM?.doubleValue), stance, accuracy: 1e-9)
            XCTAssertEqual(try XCTUnwrap(sample.metrics.behindServiceLineM?.doubleValue), behind, accuracy: 1e-9)
        }
        // The frame under the playhead, which at 0 s is the panned one.
        XCTAssertEqual(model.pose?.frame, 0)
        XCTAssertEqual(try XCTUnwrap(model.metrics?.behindServiceLineM?.doubleValue), behind, accuracy: 1e-9)
        // And the pan is large enough to matter, so the two lines above are
        // not passing on a fit that ignores it.
        let unmapped = PoseMetricsKt.poseMetrics(
            keypoints: panned.keypoints, confidence: panned.confidence,
            homography: loaded.homography,
            minConfidence: NearPlayerSelector.companion.MIN_KEYPOINT_CONFIDENCE
        )
        XCTAssertGreaterThan(abs(try XCTUnwrap(unmapped.behindServiceLineM?.doubleValue) - behind), 0.05)
    }

    // MARK: - Fixtures

    private func prefs() -> RacketArmPreferenceRepository {
        IosTestDoublesKt.testRacketArmPreferences()
    }

    private func poses(_ count: Int) -> [PlayerPose] {
        let joints = Int(Coco.shared.COUNT)
        return (0..<count).map { frame in
            PlayerPose(
                frame: Int32(frame),
                timestamp: Double(frame) / 30,
                keypoints: (0..<joints).map { Point(x: Double($0) + Double(frame), y: Double($0) * 2) },
                confidence: (0..<joints).map { _ in KotlinFloat(float: 0.9) },
                camera: nil
            )
        }
    }

    /// A figure whose ankles stand on the near half, `dx`, `dy` off where they
    /// stood in the marking frame, with the camera that moved them there.
    private func standing(frame: Int32, dx: Double, dy: Double) -> PlayerPose {
        let joints = Int(Coco.shared.COUNT)
        var keypoints = (0..<joints).map { _ in Point(x: 950 + dx, y: 850 + dy) }
        keypoints[Int(Coco.shared.LEFT_ANKLE)] = Point(x: 900 + dx, y: 900 + dy)
        keypoints[Int(Coco.shared.RIGHT_ANKLE)] = Point(x: 1000 + dx, y: 905 + dy)
        return PlayerPose(
            frame: frame,
            timestamp: Double(frame) / 30,
            keypoints: keypoints,
            confidence: (0..<joints).map { _ in KotlinFloat(float: 0.9) },
            camera: [[1, 0, dx], [0, 1, dy], [0, 0, 1]].map { $0.map { KotlinDouble(double: $0) } }
        )
    }

    /// 743d7fb1's marks, which the analysis tests fit a court to.
    private func fittingMarks() -> AnalysisCourtKeypoints {
        func point(_ x: Double, _ y: Double) -> Point { Point(x: x, y: y) }
        return AnalysisCourtKeypoints(
            topLeft: point(649.5, 484.8), topRight: point(1277.3, 481.2),
            bottomRight: point(1579.4, 998.6), bottomLeft: point(360.0, 1004.0),
            netLeft: point(550.0, 663.9), netRight: point(1382.2, 665.7),
            serviceLineNearLeft: point(504.8, 743.5), serviceLineNearRight: point(1422.0, 738.1),
            serviceLineFarLeft: point(588.0, 595.2), serviceLineFarRight: point(1338.8, 595.2),
            centerNear: point(966.1, 593.4), centerFar: point(966.1, 736.3)
        )
    }

    /// Twelve marks on one line. No homography fits them, which is what makes
    /// this the `.bad` case rather than the `.ok` one.
    private func unfittableMarks() -> AnalysisCourtKeypoints {
        func point(_ x: Double, _ y: Double) -> Point { Point(x: x, y: y) }
        return AnalysisCourtKeypoints(
            topLeft: point(1, 2), topRight: point(3, 4),
            bottomRight: point(5, 6), bottomLeft: point(7, 8),
            netLeft: point(9, 10), netRight: point(11, 12),
            serviceLineNearLeft: point(13, 14), serviceLineNearRight: point(15, 16),
            serviceLineFarLeft: point(17, 18), serviceLineFarRight: point(19, 20),
            centerNear: point(21, 22), centerFar: point(23, 24)
        )
    }
}

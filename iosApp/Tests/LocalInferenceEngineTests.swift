import Shared
import XCTest
@testable import iosApp

/// The whole engine, end to end, on the simulator.
///
/// This is the test the rest of the port leans on. Everything downstream of
/// `RawInference` - the shuttle track filtering, both rally detectors, clip
/// windows, the near-player selection - is one Kotlin implementation shared
/// with Android, so if the engine produces a well-formed `RawInference` for a
/// video, the analysis that follows is already covered by `:analysis`' own
/// suites.
///
/// What it cannot check is agreement with Android on real footage. That needs
/// the corpus video and two phones, and it is the gate the design's section 6
/// names as the real one. This checks the structural contract, which is what
/// fails first and loudest when a decoder, a tensor layout or a pointer offset
/// is wrong.
final class LocalInferenceEngineTests: XCTestCase {

    private var scratch: URL!

    override func setUpWithError() throws {
        try XCTSkipUnless(
            ModelCatalog.canAnalyseOnDevice,
            "no ONNX graphs staged: build with the models exported"
        )
        scratch = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("LocalInferenceEngineTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        if let scratch { try? FileManager.default.removeItem(at: scratch) }
    }

    private func video(frames: Int, width: Int = 320, height: Int = 180, pan: Int? = nil) throws -> URL {
        let url = scratch.appendingPathComponent("clip.mp4")
        try TestVideo.write(
            frames: frames, width: width, height: height, panPixelsPerFrame: pan, to: url, test: self
        )
        return url
    }

    /// A court filling most of the frame. The values do not have to describe a
    /// real court for the engine's own contract - nothing in the platform layer
    /// reads them - but they do have to form a sane quadrilateral, because the
    /// coordinator hands them to `:analysis` on the way out.
    private func court(width: Double, height: Double) -> CourtKeypoints {
        func point(_ x: Double, _ y: Double) -> [KotlinFloat] {
            [KotlinFloat(float: Float(x * width)), KotlinFloat(float: Float(y * height))]
        }
        return CourtKeypoints(
            topLeft: point(0.2, 0.2), topRight: point(0.8, 0.2),
            bottomRight: point(0.95, 0.9), bottomLeft: point(0.05, 0.9),
            netLeft: point(0.1, 0.55), netRight: point(0.9, 0.55),
            serviceLineNearLeft: point(0.08, 0.75), serviceLineNearRight: point(0.92, 0.75),
            serviceLineFarLeft: point(0.22, 0.3), serviceLineFarRight: point(0.78, 0.3),
            centerNear: point(0.5, 0.9), centerFar: point(0.5, 0.2),
            markedAtSeconds: nil
        )
    }

    func testTheEngineEmitsOneRecordPerDecodedFrame() throws {
        let frames = 12
        let url = try video(frames: frames)
        let raw = try IosLocalInferenceEngine().rawInference(videoPath: url.path, markingTimeS: nil) { _ in }

        // Every decoded frame gets a record, present or absent. A sparse list
        // would make the rally detectors' frame arithmetic wrong: they index by
        // position, not by key.
        XCTAssertEqual(raw.frames.count, frames)
        XCTAssertEqual(raw.header.totalFrames, Int32(frames))
        XCTAssertEqual(raw.frames.map { Int($0.frame) }, Array(0..<frames))
        XCTAssertEqual(raw.header.videoWidth, 320)
        XCTAssertEqual(raw.header.videoHeight, 180)
        XCTAssertEqual(raw.header.modelVersion, ModelCatalog.version)
    }

    func testTimestampsComeFromTheContainerAndIncrease() throws {
        let url = try video(frames: 12)
        let raw = try IosLocalInferenceEngine().rawInference(videoPath: url.path, markingTimeS: nil) { _ in }
        let timestamps = raw.frames.map(\.timestamp)
        XCTAssertEqual(timestamps, timestamps.sorted())
        XCTAssertEqual(Set(timestamps).count, timestamps.count)
        XCTAssertEqual(timestamps.first ?? -1, 0, accuracy: 1e-6)
    }

    /// The trailing sequence is padded by repeating its last frame, and the
    /// padded outputs must be discarded rather than emitted.
    ///
    /// 12 frames is 8 plus a 4-frame tail, so this exercises the pad path. If
    /// the padding leaked, there would be 16 shuttle records over a 12-frame
    /// video - shuttle positions fabricated past the end.
    func testATrailingPartialSequenceDoesNotFabricateFrames() throws {
        let url = try video(frames: 12)
        let raw = try IosLocalInferenceEngine().rawInference(videoPath: url.path, markingTimeS: nil) { _ in }
        XCTAssertEqual(raw.frames.count, 12)
        XCTAssertTrue(raw.frames.allSatisfy { $0.frame < 12 })
    }

    func testProgressIsReportedAndNeverReachesOne() throws {
        // The coordinator owns reporting completion, after the analysis that
        // follows inference. An engine reporting 1.0 would show a finished bar
        // over minutes of remaining work.
        let url = try video(frames: 24)
        let progress = Progress()
        _ = try IosLocalInferenceEngine().rawInference(videoPath: url.path, markingTimeS: nil) { progress.record($0) }
        XCTAssertFalse(progress.values.isEmpty, "the engine reported no progress at all")
        XCTAssertTrue(progress.values.allSatisfy { $0 < 1 }, "engine reported completion: \(progress.values)")
        XCTAssertEqual(progress.values, progress.values.sorted())
    }

    /// Collected off the analysis queue, so it needs its own lock.
    private final class Progress: @unchecked Sendable {
        private let lock = NSLock()
        private var seen: [Float] = []
        func record(_ value: Float) { lock.lock(); seen.append(value); lock.unlock() }
        var values: [Float] { lock.lock(); defer { lock.unlock() }; return seen }
    }

    func testAMissingVideoFailsRatherThanReturningAnEmptyAnalysis() throws {
        // An empty RawInference reads downstream as a video with no rallies,
        // which looks exactly like a finished analysis of a quiet match.
        do {
            _ = try IosLocalInferenceEngine()
                .rawInference(videoPath: scratch.appendingPathComponent("absent.mp4").path, markingTimeS: nil) { _ in }
            XCTFail("a missing video should fail the run")
        } catch {
            // Expected.
        }
    }

    // MARK: - Following the camera (design 2026-09-27-camera-motion §5.5)

    /// A pass with pose and a known marking time follows the camera from the
    /// marking frame, and finds the move the clip was written with.
    ///
    /// The pan is the real check. A camera that is merely present proves the
    /// marking frame had corners; one whose translation matches the written
    /// move proves the plane was read with the right stride and the right
    /// scale all the way from the locked pixel buffer into the tracker.
    func testAPoseRunWithAMarkingTimeFollowsTheCameraFromTheMarkingFrame() throws {
        let pan = 1
        let url = try video(frames: 12, pan: pan)
        let lines = LogLines()
        let raw = try IosLocalInferenceEngine(wantsPose: true, log: { lines.append($0) })
            .rawInference(videoPath: url.path, markingTimeS: 0.1) { _ in }

        let camera = try XCTUnwrap(raw.camera, "no camera from a textured clip: \(lines.all)")
        // The marking frame is the first at or after T, within the tracker's
        // own tolerance: the container may store 0.1 as 0.0999...
        let marking = try XCTUnwrap(raw.frames.map(\.timestamp).first { $0 >= 0.1 - 1e-6 })
        XCTAssertEqual(camera.referenceTimestamp, marking, accuracy: 1e-9)

        // 12 frames at 30 fps: samples at 0.0 (buffered, tracked once the
        // marking frame arrives), 0.1 (the reference), 0.2 and 0.3.
        XCTAssertGreaterThanOrEqual(camera.samples.count, 4, "\(lines.all)")
        let last = try XCTUnwrap(camera.samples.last)
        let fps = 30.0
        let movedFrames = ((last.timestamp - marking) * fps).rounded()
        let m = last.referenceToFrame.map { $0.map(\.doubleValue) }
        // Frame k is the texture moved right by k * pan, so a reference point
        // lands that much further right in a later frame.
        XCTAssertEqual(m[0][2], movedFrames * Double(pan), accuracy: 0.5, "\(m)")
        XCTAssertEqual(m[1][2], 0, accuracy: 0.5, "\(m)")
        XCTAssertTrue(
            lines.all.contains { $0.contains("camera:") && $0.contains("followed") },
            "the counts were not logged: \(lines.all)"
        )
        print("CAMERA engine 320x180: \(lines.all)")
    }

    /// Marks with no known time: nothing to anchor the camera to.
    func testAPoseRunWithNoMarkingTimeFollowsNoCamera() throws {
        let url = try video(frames: 12, pan: 1)
        let raw = try IosLocalInferenceEngine(wantsPose: true)
            .rawInference(videoPath: url.path, markingTimeS: nil) { _ in }
        XCTAssertNil(raw.camera)
    }

    /// A rallies-only pass has no skeleton for the camera to correct.
    func testARunWithoutPoseFollowsNoCamera() throws {
        let url = try video(frames: 12, pan: 1)
        let raw = try IosLocalInferenceEngine()
            .rawInference(videoPath: url.path, markingTimeS: 0.1) { _ in }
        XCTAssertNil(raw.camera)
    }

    /// A marking frame with nothing to hold on to gives no camera, and the run
    /// still succeeds: losing the camera never costs the analysis.
    func testAFlatMarkingFrameGivesNoCameraAndTheRunStillCompletes() throws {
        let url = try video(frames: 12)
        let raw = try IosLocalInferenceEngine(wantsPose: true)
            .rawInference(videoPath: url.path, markingTimeS: 0.1) { _ in }
        XCTAssertNil(raw.camera)
        XCTAssertEqual(raw.frames.count, 12)
    }

    /// Collected off the analysis queue in the coordinator's case; locked for
    /// the same reason `Progress` is.
    private final class LogLines: @unchecked Sendable {
        private let lock = NSLock()
        private var seen: [String] = []
        func append(_ line: String) { lock.lock(); seen.append(line); lock.unlock() }
        var all: [String] { lock.lock(); defer { lock.unlock() }; return seen }
    }

    /// The engine driven the way the app drives it: through the shared
    /// coordinator, which is what actually runs the analysis.
    ///
    /// The point is not the rally count - a solid grey video has no shuttle in
    /// it and should produce none - but that the Swift engine and the Kotlin
    /// coordinator fit together at all: the protocol conformance, the suspend
    /// bridge, the error channel and the court keypoint conversion.
    func testTheSharedCoordinatorDrivesTheSwiftEngine() async throws {
        let url = try video(frames: 12)
        let coordinator = LocalAnalysisCoordinatorIosKt.createIosLocalAnalysisCoordinator(
            engine: IosLocalInferenceEngine(),
            log: { _ in },
            legacyMarkingTime: { _, done in done(KotlinDouble(value: 0.1)) }
        )
        // analyzeThrowing, not analyze: Kotlin's Result reaches Objective-C as
        // a bare id, so `analyze` cannot tell success from failure in Swift.
        // See the wrapper's own note in SwiftInterop.kt.
        let result = try await coordinator.analyzeThrowing(
            videoPath: url.path,
            keypoints: court(width: 320, height: 180),
            onProgress: { _ in }
        )
        XCTAssertEqual(result.result.totalFrames, 12)
        // No shuttle anywhere in a solid grey frame, so no rallies. Asserted
        // rather than merely expected: a detector that hallucinated on flat
        // input would show up here as rallies out of nothing.
        XCTAssertTrue(result.result.rallies.isEmpty)
        XCTAssertTrue(result.clipWindows.isEmpty)
        // Pose was not asked for, so the track is empty rather than absent.
        XCTAssertEqual(result.playerTrack.framesWithPose, 0)
    }
}

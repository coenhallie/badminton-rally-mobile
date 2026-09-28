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

    // MARK: - The handheld recording, against the reference

    /// Whether this build has `-D RUN_HANDHELD_CORPUS_TEST`; a value, not an `#if` fence, so the test always compiles.
    private var handheldCorpusRunEnabled: Bool {
        #if RUN_HANDHELD_CORPUS_TEST
        return true
        #else
        return false
        #endif
    }

    /// The corpus this test reads, on the Mac running the simulator.
    ///
    /// Through `SIMULATOR_HOST_HOME` rather than `NSHomeDirectory()`, which in
    /// a simulator process is the app's own container. A simulator process can
    /// read the host's files directly, so the 1080p video is read in place
    /// rather than copied into the container first.
    private var corpus: URL? {
        ProcessInfo.processInfo.environment["SIMULATOR_HOST_HOME"].map {
            URL(fileURLWithPath: $0).appendingPathComponent("shuttl-corpus/IMG_5892", isDirectory: true)
        }
    }

    /// `IMG_5892`, a handheld recording that drifts and pans, analysed the way
    /// the app analyses it: this engine with pose, through the shared
    /// coordinator, the court marked at 0.1 s with the corpus's ideal marks.
    /// The near track is held against `reference_tracks.json`'s `ref_track`,
    /// the same selection with the camera followed by every-frame ORB. Design
    /// `2026-09-27-camera-motion-design.md` §8.5: at least 95% of pose frames
    /// produce a position, median at most 5 cm, p95 at most 15 cm.
    ///
    /// The one test that runs the iOS luma path on real footage. Android reads
    /// its planes differently (padded decoder frames, a different stride), so
    /// the Kotlin corpus tests cannot vouch for this one.
    ///
    /// Joined by frame: this engine numbers frames from 0 in decode order, as
    /// `ref_track` does, so frame `k` is reference `k` (the cloud's frames are
    /// the ones numbered from 1).
    ///
    /// Opt-in, like `ThroughputMeasurementTests`: skipped unless the app is
    /// built with `-D RUN_HANDHELD_CORPUS_TEST`, then skipped again unless the
    /// corpus is on this Mac. The corpus alone cannot be the gate, because
    /// `SIMULATOR_HOST_HOME` is set in every simulator run: on a Mac holding
    /// the corpus every ordinary unit run would pay minutes for it, since each
    /// of the 2,182 frames pays TrackNet, the detector and pose. A compile flag
    /// rather than an environment variable for the reason that file gives:
    /// `xcodebuild test` forwards neither into a unit-test bundle hosted in an
    /// app.
    ///
    /// Run it in the Release configuration, since the camera tracker in a
    /// Debug Kotlin framework is some 28 times slower (391 to 489 s measured
    /// on the simulator in Release, about 6.5 to 8 minutes):
    ///
    ///     xcodebuild test -project iosApp/iosApp.xcodeproj -scheme iosApp \
    ///       -configuration Release ENABLE_TESTABILITY=YES \
    ///       -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
    ///       -only-testing:iosAppTests/LocalInferenceEngineTests/testAHandheldRecordingPutsTheNearPlayerWhereTheReferenceHasHer \
    ///       CODE_SIGNING_ALLOWED=NO \
    ///       OTHER_SWIFT_FLAGS='$(inherited) -D RUN_HANDHELD_CORPUS_TEST'
    func testAHandheldRecordingPutsTheNearPlayerWhereTheReferenceHasHer() async throws {
        try XCTSkipUnless(
            handheldCorpusRunEnabled,
            "build with -D RUN_HANDHELD_CORPUS_TEST to analyse the handheld corpus recording"
        )
        guard let directory = corpus else {
            throw XCTSkip("SIMULATOR_HOST_HOME is not set: this test reads the corpus off the host Mac")
        }
        let video = directory.appendingPathComponent("source.mov")
        let marksFile = directory.appendingPathComponent("marks_ideal.json")
        let referenceFile = directory.appendingPathComponent("reference_tracks.json")
        let missing = [video, marksFile, referenceFile].filter {
            !FileManager.default.isReadableFile(atPath: $0.path)
        }
        try XCTSkipUnless(
            missing.isEmpty,
            "corpus file(s) \(missing.map(\.path).joined(separator: ", ")) not present, so the "
                + "handheld recording was not analysed on the simulator"
        )

        let marks = try corpusMarks(marksFile, markedAtSeconds: 0.1)
        let reference = try corpusReference(referenceFile)

        // Every line to the system log as well, as the app's runner sends it:
        // the test host is the app, so `log show` finds this run's camera line.
        let lines = LogLines()
        let log: @Sendable (String) -> Void = { line in
            lines.append(line)
            LocalAnalysisRunner.systemLog(line)
            print("LocalAnalysis: \(line)")
        }
        let coordinator = LocalAnalysisCoordinatorIosKt.createIosLocalAnalysisCoordinator(
            engine: IosLocalInferenceEngine(wantsPose: true, log: log),
            log: log,
            // Never asked: the marks carry their time.
            legacyMarkingTime: { _, done in done(KotlinDouble(value: 0.1)) }
        )
        let started = Date()
        let result = try await coordinator.analyzeThrowing(
            videoPath: video.path, keypoints: marks, onProgress: { _ in }
        )
        let elapsed = Date().timeIntervalSince(started)

        // The camera was followed. Without this a run that fell back to the
        // fixed marks would be held to the bounds by luck alone, and on this
        // recording it cannot meet them that way.
        let cameraLine = lines.all.first { $0.hasPrefix("camera:") && $0.contains("samples followed") }
        XCTAssertNotNil(cameraLine, "no camera line: \(lines.all)")
        XCTAssertFalse(cameraLine?.hasSuffix(", no camera") ?? true, "the camera was not followed: \(cameraLine ?? "")")

        let track = result.playerTrack
        var errors: [Double] = []
        var withoutReference = 0
        for sample in track.samples {
            guard let expected = reference[Int(sample.frame)] else {
                withoutReference += 1
                continue
            }
            errors.append(hypot(sample.courtPosition.x - expected.x, sample.courtPosition.y - expected.y))
        }
        let totalFrames = Int(result.result.totalFrames)
        print(String(
            format: "HANDHELD: %d frames in %.0f s (%.1f ms per frame); NEAR: %d positions over %d pose frames "
                + "(coverage %.4f), %.4f of every frame; rejections %@",
            totalFrames, elapsed, elapsed * 1000 / Double(max(totalFrames, 1)),
            track.samples.count, Int(track.framesWithPose), track.coverage,
            Double(track.samples.count) / Double(max(totalFrames, 1)), "\(track.rejections)"
        ))
        print("HANDHELD: \(cameraLine ?? "no camera line")")
        let median = percentile(errors, 50)
        let p95 = percentile(errors, 95)
        print(String(
            format: "HANDHELD against ref_track: median %.1f cm, p95 %.1f cm, worst %.1f cm, over %d frames "
                + "(%d positions had no reference)",
            median * 100, p95 * 100, (errors.max() ?? .nan) * 100, errors.count, withoutReference
        ))

        // All three, and none of them fatal, so a coverage miss cannot hide an
        // accuracy one.
        XCTAssertFalse(errors.isEmpty, "no position met the reference")
        XCTAssertGreaterThanOrEqual(track.coverage, 0.95, "coverage")
        XCTAssertLessThanOrEqual(median, 0.05, "median error in metres")
        XCTAssertLessThanOrEqual(p95, 0.15, "p95 error in metres")
        // A frame the reference lacks is left out of the bounds above, so a
        // join gone wrong (off by one, say) must not pass by shrinking them.
        XCTAssertLessThanOrEqual(
            Double(withoutReference), 0.01 * Double(track.samples.count),
            "\(withoutReference) of \(track.samples.count) positions had no reference frame"
        )
    }

    /// `marks_ideal.json`'s `manual_court_keypoints`, source pixels, by the
    /// names `:analysis`' `CourtKeypoints.fromMap` reads them by.
    private func corpusMarks(_ file: URL, markedAtSeconds: Double) throws -> CourtKeypoints {
        let json = try XCTUnwrap(
            try JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any]
        )
        let points = try XCTUnwrap(json["manual_court_keypoints"] as? [String: [Double]], "\(file.path)")
        func point(_ name: String) throws -> [KotlinFloat] {
            let xy = try XCTUnwrap(points[name], "\(name) missing from \(file.path)")
            return [KotlinFloat(float: Float(xy[0])), KotlinFloat(float: Float(xy[1]))]
        }
        return CourtKeypoints(
            topLeft: try point("top_left"), topRight: try point("top_right"),
            bottomRight: try point("bottom_right"), bottomLeft: try point("bottom_left"),
            netLeft: try point("net_left"), netRight: try point("net_right"),
            serviceLineNearLeft: try point("service_line_near_left"),
            serviceLineNearRight: try point("service_line_near_right"),
            serviceLineFarLeft: try point("service_line_far_left"),
            serviceLineFarRight: try point("service_line_far_right"),
            centerNear: try point("center_near"), centerFar: try point("center_far"),
            markedAtSeconds: KotlinDouble(value: markedAtSeconds)
        )
    }

    /// `ref_track`: 0-based frame index to the near player's court position in
    /// metres.
    private func corpusReference(_ file: URL) throws -> [Int: (x: Double, y: Double)] {
        let json = try XCTUnwrap(
            try JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any]
        )
        let track = try XCTUnwrap(json["ref_track"] as? [String: [Double]], "\(file.path)")
        var reference: [Int: (x: Double, y: Double)] = [:]
        for (key, xy) in track {
            reference[try XCTUnwrap(Int(key), "frame key \(key)")] = (xy[0], xy[1])
        }
        return reference
    }

    /// Linearly interpolated, numpy's default, so the figures match the
    /// Kotlin corpus tests' and the tracker repo's.
    private func percentile(_ values: [Double], _ p: Double) -> Double {
        let sorted = values.sorted()
        guard let first = sorted.first else { return .nan }
        if sorted.count == 1 { return first }
        let rank = p / 100 * Double(sorted.count - 1)
        let lower = Int(rank.rounded(.down))
        let upper = Int(rank.rounded(.up))
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (rank - Double(lower))
    }
}

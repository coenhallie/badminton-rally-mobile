import AVFoundation
import CoreVideo
import Shared
import XCTest
@testable import iosApp

/// That the decode pass sees every frame, in order, with the container's own
/// timestamps.
///
/// Android's equivalent is instrumented and holds the corpus video to its
/// recorded 5,972 frames at 29.73572449542545 fps - the number stage 1 pinned
/// against both the container and results.json. That file is not committable,
/// so these build a small video instead and hold the reader to what it was
/// written with. It is a weaker oracle for fps and an equally strong one for
/// the properties that actually break a pipeline: a dropped frame shifts every
/// index after it, and a fabricated timestamp puts a skeleton on the wrong
/// frame.
final class VideoFrameSourceTests: XCTestCase {

    private var scratch: URL!

    override func setUpWithError() throws {
        scratch = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("VideoFrameSourceTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: scratch)
    }

    private func writeVideo(frames: Int, fps: Int32 = 30, size: Int = 64) throws -> URL {
        let url = scratch.appendingPathComponent("clip.mp4")
        try TestVideo.write(frames: frames, fps: fps, width: size, height: size, to: url, test: self)
        return url
    }

    func testEverySequentialFrameArrivesOnceInOrder() throws {
        let source = VideoFrameSource(url: try writeVideo(frames: 24))
        var indices: [Int] = []
        var timestamps: [Double] = []
        try source.forEachFrame { frame in
            indices.append(frame.index)
            timestamps.append(frame.seconds)
        }
        XCTAssertEqual(indices, Array(0..<24))
        // Strictly increasing, from the container rather than index / fps. On a
        // variable-frame-rate source those disagree, and a repeated or
        // backwards timestamp is how a skeleton ends up drawn on the wrong
        // frame.
        XCTAssertEqual(timestamps, timestamps.sorted())
        XCTAssertEqual(Set(timestamps).count, timestamps.count)
        XCTAssertEqual(try XCTUnwrap(timestamps.first), 0, accuracy: 1e-6)
    }

    func testMetadataCountsEveryFrameAndDerivesFpsFromTheCount() throws {
        let source = VideoFrameSource(url: try writeVideo(frames: 30, fps: 30))
        let metadata = try source.metadata()
        XCTAssertEqual(metadata.frameCount, 30)
        XCTAssertEqual(metadata.width, 64)
        XCTAssertEqual(metadata.height, 64)
        // Frames over container duration, matching Android. Not
        // nominalFrameRate, which reports a round number on files whose real
        // rate is not one.
        XCTAssertEqual(metadata.fps, 30, accuracy: 0.5)
    }

    func testMaxFramesStopsThePassEarly() throws {
        let source = VideoFrameSource(url: try writeVideo(frames: 24))
        var count = 0
        try source.forEachFrame(maxFrames: 5) { _ in count += 1 }
        XCTAssertEqual(count, 5)
    }

    func testBackgroundSamplingReturnsExactlyTheRequestedFrames() throws {
        // The whole reason this pass reads sequentially rather than seeking:
        // production selects frames BY INDEX, and every seek-based API here is
        // time-based, which lands elsewhere on a variable-frame-rate source.
        // Each written frame carries its index in its luma value, so this can
        // assert it got the frames it asked for rather than merely the right
        // number of them.
        let source = VideoFrameSource(url: try writeVideo(frames: 20))
        let wanted = [0, 7, 19]
        let values = try source.sampleFramesForBackground(indices: wanted) { buffer -> UInt8 in
            let luma = CVPixelBufferGetBaseAddressOfPlane(buffer, 0)!.assumingMemoryBound(to: UInt8.self)
            return luma[0]
        }
        XCTAssertEqual(values.count, wanted.count)
        let recovered = values.map { TestVideo.frame(forLuma: $0) }
        XCTAssertEqual(recovered, wanted)
    }

    func testTheSampledIndicesComeFromTheSharedArithmetic() throws {
        // Not a test of Swift: a test that iOS calls :analysis' own
        // backgroundSampleIndices rather than growing a second copy. The two
        // platforms must sample the same frames or they compute different
        // backgrounds, and every heatmap follows the background.
        let indices = MedianBackgroundKt.backgroundSampleIndices(totalFrames: 5972, maxSamples: 300)
        XCTAssertEqual(indices.count, 300)
        XCTAssertEqual(indices.first?.intValue, 0)
        // np.linspace truncates: step is 5971/299, so index 1 is 19.97 -> 19.
        XCTAssertEqual(indices[1].intValue, 19)
        XCTAssertEqual(indices.last?.intValue, 5971)
    }

    func testAMissingFileFailsRatherThanReportingAnEmptyVideo() throws {
        let source = VideoFrameSource(url: scratch.appendingPathComponent("absent.mp4"))
        // An empty frame list would be read downstream as a video with no
        // rallies in it, which looks like a finished analysis.
        XCTAssertThrowsError(try source.metadata())
    }

    // MARK: - The camera tracker's read of the decoded plane

    /// The camera tracker fed straight from this pass's locked pixel buffers,
    /// at the size a phone records.
    ///
    /// Here rather than beside the engine tests because it needs no model, so
    /// it runs in a build without the ONNX graphs too, and it is the one check
    /// on a real decoded `CVPixelBuffer`: a 1080p plane that decoded as 1088
    /// rows, or a padded stride, would show here as a camera that stopped at
    /// the first frame.
    func testTheCameraIsFollowedFromTheDecodedLumaPlaneAt1080p() throws {
        let url = scratch.appendingPathComponent("textured.mp4")
        let pan = 2
        try TestVideo.write(
            frames: 30, width: 1920, height: 1080, panPixelsPerFrame: pan, to: url, test: self
        )
        let source = VideoFrameSource(url: url)
        let metadata = try source.metadata()
        var lines: [String] = []
        let follower = try XCTUnwrap(CameraFollower(
            markingTimeS: 0.1, sourceWidth: metadata.width, sourceHeight: metadata.height,
            log: { lines.append($0) }
        ))
        try source.forEachFrame { frame in follower.follow(seconds: frame.seconds, buffer: frame.pixelBuffer) }
        let camera = try XCTUnwrap(follower.finish(), "\(lines)")

        // One second at 10 Hz: every bucket from 0.0 to 0.9 is a sample.
        XCTAssertEqual(camera.samples.count, 10, "\(lines)")
        let last = try XCTUnwrap(camera.samples.last)
        let movedFrames = ((last.timestamp - camera.referenceTimestamp) * 30).rounded()
        let m = last.referenceToFrame.map { $0.map(\.doubleValue) }
        // In source pixels: the tracker's scale is undone on the way out.
        XCTAssertEqual(m[0][2], movedFrames * Double(pan), accuracy: 1, "\(m)")
        XCTAssertEqual(m[1][2], 0, accuracy: 1, "\(m)")
        // The Kotlin framework is built in the configuration the test is:
        // Debug Kotlin/Native is unoptimised and many times slower, so a
        // figure means nothing without it.
        #if DEBUG
        let configuration = "Debug"
        #else
        let configuration = "Release"
        #endif
        print("CAMERA simulator 1920x1080, \(configuration): \(lines)")
    }

    /// A plane that is not the size the tracker was built for stops the
    /// tracker, once, rather than reading a wrongly scaled image: a camera from
    /// that would put every skeleton in the wrong place with confidence.
    func testAPlaneOfTheWrongSizeStopsTheCameraRatherThanScalingIt() throws {
        let source = VideoFrameSource(url: try writeVideo(frames: 12))
        var lines: [String] = []
        // The clip is 64x64; the tracker is told otherwise.
        let follower = try XCTUnwrap(CameraFollower(
            markingTimeS: 0.1, sourceWidth: 128, sourceHeight: 64, log: { lines.append($0) }
        ))
        try source.forEachFrame { frame in follower.follow(seconds: frame.seconds, buffer: frame.pixelBuffer) }
        XCTAssertNil(follower.finish())
        XCTAssertEqual(lines.filter { $0.contains("64x64") }.count, 1, "\(lines)")
    }

    /// No size, no tracker: its constructor would throw into Swift, where a
    /// Kotlin throw from a function not marked `@Throws` ends the process.
    func testAnEmptySourceSizeBuildsNoCameraFollower() {
        XCTAssertNil(CameraFollower(markingTimeS: 0.1, sourceWidth: 0, sourceHeight: 1080, log: { _ in }))
    }
}

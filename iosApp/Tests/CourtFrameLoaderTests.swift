import AVFoundation
import Shared
import UIKit
import XCTest
@testable import iosApp

/// The time the court marks carry: which frame of the video the marking still
/// really is, since the loader lets AVFoundation answer 0.1 s with a later
/// sync frame (camera-motion design §3).
///
/// The oracle is the pixels, not the generator's reported time: `TestVideo`
/// paints each frame with its own grey, so the still is identified by which
/// frame, decoded at its exact time, it looks like, and that frame's
/// presentation time is what the marks must carry. Asking the loader's
/// generator a second time would only check it agrees with itself.
final class CourtFrameLoaderTests: XCTestCase {

    private static let fps: Int32 = 10
    private static let frames = 25

    /// Documents-relative, because that is what `loadFirstFrame` resolves.
    private var relativePath: String!

    override func setUpWithError() throws {
        relativePath = "CourtFrameLoaderTests-\(UUID().uuidString).mp4"
        // One sync frame after the first, two seconds in: the shape of a phone
        // recording whose still came back from 1.0 s rather than 0.1 s.
        try TestVideo.write(
            frames: Self.frames, fps: Self.fps, maxKeyFrameInterval: 20,
            to: LocalVideoFiles.resolve(relativePath: relativePath), test: self
        )
    }

    override func tearDownWithError() throws {
        if let relativePath { LocalVideoFiles.delete(relativePath: relativePath) }
    }

    func testTheStillCarriesTheTimeOfTheFrameItIs() async throws {
        let frame = try await CourtFrameLoader.loadFirstFrame(relativePath: relativePath)

        let index = try await frameIndex(of: frame.image)
        // Never before the 0.1 s asked for: the tolerance before is zero.
        XCTAssertGreaterThanOrEqual(index, 1)
        XCTAssertEqual(frame.markedAtSeconds, Double(index) / Double(Self.fps), accuracy: 1e-9)
    }

    func testMarksDrawnOnTheStillCarryItsTime() async throws {
        let frame = try await CourtFrameLoader.loadFirstFrame(relativePath: relativePath)

        var marking = frame.marking(restoring: nil)
        for i in 0..<12 {
            marking = marking.place(
                displayX: Float(i), displayY: Float(i * 2), displayWidth: 64, displayHeight: 64
            )
        }

        XCTAssertEqual(marking.toCourtKeypoints().markedAtSeconds?.doubleValue, frame.markedAtSeconds)
    }

    func testRestoredMarksKeepTheirOwnTime() async throws {
        let frame = try await CourtFrameLoader.loadFirstFrame(relativePath: relativePath)
        let p = [KotlinFloat(float: 1), KotlinFloat(float: 2)]
        let saved = CourtKeypoints(
            topLeft: p, topRight: p, bottomRight: p, bottomLeft: p,
            netLeft: p, netRight: p,
            serviceLineNearLeft: p, serviceLineNearRight: p,
            serviceLineFarLeft: p, serviceLineFarRight: p,
            centerNear: p, centerFar: p,
            markedAtSeconds: KotlinDouble(value: 0.5)
        )

        let marking = frame.marking(restoring: saved)

        XCTAssertTrue(marking.isComplete)
        XCTAssertEqual(marking.toCourtKeypoints().markedAtSeconds?.doubleValue, 0.5)
    }

    /// What the runner asks for marks saved without a time: the same frame the
    /// screen showed, from the absolute path the run holds.
    func testTheLegacyLookupNamesTheFrameTheScreenShowed() async throws {
        let frame = try await CourtFrameLoader.loadFirstFrame(relativePath: relativePath)

        let seconds = await CourtFrameLoader.stillSeconds(
            url: LocalVideoFiles.resolve(relativePath: relativePath)
        )

        XCTAssertEqual(seconds, frame.markedAtSeconds)
    }

    func testTheLegacyLookupOfAnUnreadableVideoIsTheDefaultFrame() async {
        let seconds = await CourtFrameLoader.stillSeconds(
            url: URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("absent.mp4")
        )

        XCTAssertEqual(seconds, 0.1)
    }

    /// Which frame of the clip `image` is: the one whose exact decode has
    /// the nearest grey.
    ///
    /// Matched against exact decodes rather than inverted from `TestVideo`'s
    /// luma, because the still reaches RGB through a colour conversion this
    /// test does not own; decoded the same way, every candidate goes through
    /// it too. Neighbouring frames differ by a whole luma step of 8.
    private func frameIndex(of image: UIImage) async throws -> Int {
        let still = try grey(XCTUnwrap(image.cgImage))
        let generator = AVAssetImageGenerator(
            asset: AVURLAsset(url: LocalVideoFiles.resolve(relativePath: relativePath))
        )
        generator.appliesPreferredTrackTransform = true
        generator.requestedTimeToleranceBefore = .zero
        generator.requestedTimeToleranceAfter = .zero
        var best = (index: -1, distance: Double.infinity)
        for index in 0..<Self.frames {
            let exact = try await generator.image(at: CMTime(value: CMTimeValue(index), timescale: Self.fps)).image
            let distance = abs(try grey(exact) - still)
            if distance < best.distance { best = (index, distance) }
        }
        XCTAssertLessThan(best.distance, 2, "no decoded frame looks like the still")
        return best.index
    }

    /// The whole image averaged into one pixel's red channel: the frame is one
    /// solid grey, and an average is what H.264's noise cannot move.
    private func grey(_ image: CGImage) throws -> Double {
        var pixel = [UInt8](repeating: 0, count: 4)
        let context = try XCTUnwrap(CGContext(
            data: &pixel, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ))
        context.interpolationQuality = .medium
        context.draw(image, in: CGRect(x: 0, y: 0, width: 1, height: 1))
        return Double(pixel[0])
    }
}

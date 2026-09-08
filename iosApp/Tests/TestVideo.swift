import AVFoundation
import CoreVideo
import XCTest

/// A short H.264 clip written on the fly, so the decode and inference tests do
/// not need the corpus video.
///
/// The corpus is not committable - it is real match footage carrying real
/// people - so Android's equivalents are instrumented tests that expect it
/// side-loaded onto a device. These write their own, which costs a weaker
/// oracle for anything that depends on real content (shuttle positions, rally
/// counts) and an equally strong one for everything structural: frame counts,
/// index alignment, timestamp monotonicity, tensor shapes.
///
/// Each frame is a solid grey whose value encodes its own index, so a decoded
/// frame can be identified from its pixels rather than trusted to be the one
/// that was asked for.
enum TestVideo {

    static let lumaBase = 16
    static let lumaStep = 8

    /// How many frames the luma encoding can tell apart.
    ///
    /// `16 + 29 * 8` is 248, and the next step does not fit in a byte. The
    /// values are spaced by 8 so H.264's quantisation cannot make two levels
    /// indistinguishable; packing them tighter would buy more frames and lose
    /// the property the encoding exists for.
    static let identifiableFrames = 30

    /// The luma an index was written with, and back again, so a test can say
    /// which frame it is holding.
    ///
    /// Wraps past `identifiableFrames`, so a longer clip is still a valid video
    /// - it is just no longer one whose frames can be identified from their
    /// pixels. Only the decode tests need that; the throughput measurement
    /// wants length.
    static func luma(forFrame index: Int) -> UInt8 {
        UInt8(lumaBase + (index % identifiableFrames) * lumaStep)
    }

    /// Unambiguous only for a clip of at most `identifiableFrames` frames.
    static func frame(forLuma luma: UInt8) -> Int {
        // H.264 is lossy, so the value is matched to the nearest written level.
        Int(((Double(luma) - Double(lumaBase)) / Double(lumaStep)).rounded())
    }

    static func write(
        frames: Int,
        fps: Int32 = 30,
        width: Int = 64,
        height: Int = 64,
        to url: URL,
        test: XCTestCase
    ) throws {
        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
        ])
        input.expectsMediaDataInRealTime = false
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: input,
            sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String:
                    Int(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange),
                kCVPixelBufferWidthKey as String: width,
                kCVPixelBufferHeightKey as String: height,
            ]
        )
        writer.add(input)
        XCTAssertTrue(writer.startWriting())
        writer.startSession(atSourceTime: .zero)

        for index in 0..<frames {
            var buffer: CVPixelBuffer?
            CVPixelBufferPoolCreatePixelBuffer(nil, adaptor.pixelBufferPool!, &buffer)
            let pixels = try XCTUnwrap(buffer)
            CVPixelBufferLockBaseAddress(pixels, [])
            let lumaPlane = CVPixelBufferGetBaseAddressOfPlane(pixels, 0)!
                .assumingMemoryBound(to: UInt8.self)
            let lumaStride = CVPixelBufferGetBytesPerRowOfPlane(pixels, 0)
            let value = luma(forFrame: index)
            for row in 0..<height {
                for column in 0..<width { lumaPlane[row * lumaStride + column] = value }
            }
            let chromaPlane = CVPixelBufferGetBaseAddressOfPlane(pixels, 1)!
                .assumingMemoryBound(to: UInt8.self)
            let chromaStride = CVPixelBufferGetBytesPerRowOfPlane(pixels, 1)
            for row in 0..<(height / 2) {
                for column in 0..<(width / 2) {
                    chromaPlane[row * chromaStride + column * 2] = 128
                    chromaPlane[row * chromaStride + column * 2 + 1] = 128
                }
            }
            CVPixelBufferUnlockBaseAddress(pixels, [])
            while !input.isReadyForMoreMediaData { usleep(1000) }
            XCTAssertTrue(adaptor.append(
                pixels, withPresentationTime: CMTime(value: CMTimeValue(index), timescale: fps)
            ))
        }
        input.markAsFinished()
        let finished = test.expectation(description: "writer finishes")
        writer.finishWriting { finished.fulfill() }
        test.wait(for: [finished], timeout: 60)
        XCTAssertEqual(writer.status, .completed, "\(writer.error?.localizedDescription ?? "")")
    }
}

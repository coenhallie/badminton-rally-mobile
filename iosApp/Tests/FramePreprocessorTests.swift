import AVFoundation
import CoreVideo
import XCTest
@testable import iosApp

/// The two decode-parity findings, held to the numbers that established them.
///
/// The pipeline design's section 5.4 names decode and preprocessing a fidelity
/// surface that no gate covers, feeding every model on every frame, and asks
/// for it to be pinned before the device layer is built rather than discovered
/// later as unexplained drift in shuttle positions. Android's equivalent needs
/// a device and the corpus video; the assertions here that do not are worth
/// having in CI, where they run on every push.
final class FramePreprocessorTests: XCTestCase {

    func testResizeUsesOpenCVsPixelCentreConvention() {
        // A 2x2 upscaled to 4x4. OpenCV maps destination to source as
        // (dst + 0.5) * scale - 0.5, so the outer pixels clamp to the source
        // corners and the inner two interpolate at 0.25 and 0.75. The naive
        // dst * scale form is off by half a source pixel everywhere.
        let source: [UInt8] = [
            0, 0, 0, 100, 100, 100,
            100, 100, 100, 0, 0, 0,
        ]
        var destination = [UInt8](repeating: 0, count: 4 * 4 * 3)
        FramePreprocessor.resize(source, width: 2, height: 2, into: &destination, width: 4, height: 4)

        // Expected values from cv2.resize on this exact input, not hand-
        // computed. Android's version of this test records that the first
        // attempt asserted 50 at the centre by reasoning about it and OpenCV
        // gives 38; the implementation was right and the reasoning wrong.
        func pixel(_ row: Int, _ column: Int) -> Int { Int(destination[(row * 4 + column) * 3]) }
        XCTAssertEqual((0..<4).map { pixel(0, $0) }, [0, 25, 75, 100])
        XCTAssertEqual((0..<4).map { pixel(1, $0) }, [25, 38, 63, 75])
        XCTAssertEqual((0..<4).map { pixel(2, $0) }, [75, 63, 38, 25])
        XCTAssertEqual((0..<4).map { pixel(3, $0) }, [100, 75, 25, 0])
    }

    func testTensorIsChannelFirstScaledBy255() {
        let rgb: [UInt8] = [255, 0, 0, 0, 255, 0]
        var tensor = [Float](repeating: 0, count: 2 * 3)
        FramePreprocessor.toChwTensor(rgb, width: 2, height: 1, into: &tensor)
        // R plane, then G, then B - not interleaved.
        XCTAssertEqual(tensor, [1, 0, 0, 1, 0, 0])
    }

    func testTensorWritesAtAnOffsetWithoutTouchingWhatIsAlreadyThere() {
        // TrackNet writes nine frames into one 27-plane buffer this way, so an
        // offset write that clobbered its neighbours would corrupt the
        // background plane the model reads first.
        let rgb: [UInt8] = [255, 255, 255]
        var tensor = [Float](repeating: -1, count: 6)
        FramePreprocessor.toChwTensor(rgb, width: 1, height: 1, into: &tensor, offset: 3)
        XCTAssertEqual(tensor, [-1, -1, -1, 1, 1, 1])
    }

    // MARK: - The YUV conversion, against a synthesised frame

    /// A solid-colour biplanar frame, built by hand so the expected RGB can be
    /// computed from the coefficients rather than read off a decoder.
    private func solidFrame(y: UInt8, cb: UInt8, cr: UInt8, width: Int = 4, height: Int = 4) -> CVPixelBuffer {
        var buffer: CVPixelBuffer?
        CVPixelBufferCreate(
            kCFAllocatorDefault, width, height,
            kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary,
            &buffer
        )
        let pixels = buffer!
        CVPixelBufferLockBaseAddress(pixels, [])
        defer { CVPixelBufferUnlockBaseAddress(pixels, []) }
        let luma = CVPixelBufferGetBaseAddressOfPlane(pixels, 0)!.assumingMemoryBound(to: UInt8.self)
        let lumaStride = CVPixelBufferGetBytesPerRowOfPlane(pixels, 0)
        for row in 0..<height {
            for column in 0..<width { luma[row * lumaStride + column] = y }
        }
        let chroma = CVPixelBufferGetBaseAddressOfPlane(pixels, 1)!.assumingMemoryBound(to: UInt8.self)
        let chromaStride = CVPixelBufferGetBytesPerRowOfPlane(pixels, 1)
        for row in 0..<(height / 2) {
            for column in 0..<(width / 2) {
                chroma[row * chromaStride + column * 2] = cb
                chroma[row * chromaStride + column * 2 + 1] = cr
            }
        }
        return pixels
    }

    func testChromaIsReadFromTheInterleavedPlaneInCbCrOrder() {
        // The one structural difference from Android: three planes there,
        // Y plus interleaved CbCr here. Reading the two chroma bytes the wrong
        // way round swaps red and blue on every frame, which is subtle enough
        // that a heatmap would still look plausible.
        //
        // Y = 128, Cb = 128 (neutral), Cr = 255. Working it through BT.601
        // limited: luma expands to (128 - 16) * 255/219 = 130.4, the blue
        // difference is zero so B stays at the luma, and the red difference is
        // (255 - 128) * 255/224 = 144.6, so R = 130.4 + 1.402 * 144.6 saturates
        // and G = 130.4 - 0.714 * 144.6 = 27.
        //
        // Read the other way round the same frame gives R = 130 and B
        // saturated, so this pins the order rather than merely the arithmetic.
        // Getting it wrong swaps red and blue on every frame, which is subtle
        // enough that a heatmap would still look plausible.
        let frame = solidFrame(y: 128, cb: 128, cr: 255)
        CVPixelBufferLockBaseAddress(frame, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(frame, .readOnly) }
        var rgb = [UInt8](repeating: 0, count: 4 * 4 * 3)
        XCTAssertTrue(FramePreprocessor.toRgb(frame, into: &rgb))
        XCTAssertEqual(rgb[0], 255, "red saturates on a maximum red difference")
        XCTAssertEqual(rgb[1], 27, "green falls away from it")
        XCTAssertEqual(rgb[2], 130, "blue is untouched by the red difference")
    }

    func testLimitedRangeExpandsStudioSwingToFullRange() {
        // Y = 16 is studio black and Y = 235 studio white. Under the limited-
        // range scaling those become 0 and 255; treating the file as full range
        // would leave them at 16 and 235, which is the 6.55/255 mean error the
        // BT601-full row of the measurement table records.
        for (luma, expected) in [(UInt8(16), UInt8(0)), (UInt8(235), UInt8(255))] {
            let frame = solidFrame(y: luma, cb: 128, cr: 128)
            CVPixelBufferLockBaseAddress(frame, .readOnly)
            defer { CVPixelBufferUnlockBaseAddress(frame, .readOnly) }
            var rgb = [UInt8](repeating: 0, count: 4 * 4 * 3)
            XCTAssertTrue(FramePreprocessor.toRgb(frame, into: &rgb))
            XCTAssertEqual(rgb[0], expected)
            XCTAssertEqual(rgb[1], expected)
            XCTAssertEqual(rgb[2], expected)
        }
    }

    func testFusedResizeAgreesWithConvertThenResize() {
        // The per-frame path fuses conversion into the resize to avoid
        // converting 2.07M pixels to use 590k of them. That is only sound
        // because the transform is per-pixel and interpolation is linear, and
        // this is the assertion that the equivalence actually holds in this
        // implementation. Exact on a solid frame; the two differ only in where
        // they round, which cannot show when every input pixel is equal.
        let frame = solidFrame(y: 200, cb: 90, cr: 160, width: 16, height: 16)
        CVPixelBufferLockBaseAddress(frame, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(frame, .readOnly) }

        var full = [UInt8](repeating: 0, count: 16 * 16 * 3)
        XCTAssertTrue(FramePreprocessor.toRgb(frame, into: &full))
        var thenResized = [UInt8](repeating: 0, count: 8 * 8 * 3)
        FramePreprocessor.resize(full, width: 16, height: 16, into: &thenResized, width: 8, height: 8)

        var fused = [UInt8](repeating: 0, count: 8 * 8 * 3)
        XCTAssertTrue(FramePreprocessor.toRgbResized(frame, into: &fused, width: 8, height: 8))
        XCTAssertEqual(fused, thenResized)
    }

    func testLetterboxLeavesThePaddingUntouched() {
        // Ultralytics scales to fit and pads the short axis rather than
        // stretching to a square, because the model was trained that way and
        // stretching moves every box. The pad value is its grey 114, written by
        // the caller; this must not overwrite it.
        let frame = solidFrame(y: 235, cb: 128, cr: 128, width: 8, height: 4)
        CVPixelBufferLockBaseAddress(frame, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(frame, .readOnly) }
        let size = 8
        var canvas = [UInt8](repeating: 114, count: size * size * 3)
        XCTAssertTrue(FramePreprocessor.toRgbResizedInto(
            frame, canvas: &canvas, canvasWidth: size,
            offsetX: 0, offsetY: 2, fitWidth: 8, fitHeight: 4
        ))
        // Rows 0-1 and 6-7 are padding, rows 2-5 the image.
        for row in [0, 1, 6, 7] {
            XCTAssertEqual(canvas[row * size * 3], 114, "row \(row) should still be pad")
        }
        for row in 2...5 {
            XCTAssertEqual(canvas[row * size * 3], 255, "row \(row) should be image")
        }
    }
}

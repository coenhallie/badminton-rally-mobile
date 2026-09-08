import CoreVideo
import Foundation

/// Decoded frame to the tensor the models expect.
///
/// Port of Android's `FramePreprocessor`, which is itself a port of
/// production's path (`inference.py`): resize to the model's size with bilinear
/// interpolation, convert to RGB, scale by 1/255, emit CHW. The pipeline
/// design's section 5.4 calls this an unmeasured fidelity surface feeding every
/// model on every frame, so every step here matches a specific line over there
/// rather than being close enough.
///
/// Two findings are baked in that no reimplementation would rediscover, and
/// both took measurement rather than reading a spec. See `YuvMatrix` and
/// `resize`.
///
/// The one structural difference from Android is the chroma layout. MediaCodec
/// hands back `YUV_420_888` as three planes with per-plane strides; this
/// platform's `420YpCbCr8BiPlanarVideoRange` is Y in plane 0 and Cb/Cr
/// interleaved in plane 1. That is a different address for the same sample, not
/// a different sample, so the arithmetic below is Android's and only the
/// indexing changes.
enum FramePreprocessor {

    /// How to interpret the decoder's YUV. Measured, not assumed, and the
    /// answer is not the one the file asks for.
    ///
    /// The corpus video declares `color_space=bt709` and `color_range=tv`, so
    /// BT.709 limited looks like the obvious choice. Measured against
    /// production's own output over 24 frames it is not:
    ///
    /// | variant           | mean     | max   |
    /// |-------------------|----------|-------|
    /// | BT601 full        | 6.55     | 19    |
    /// | **BT601 limited** | **1.56** | **4** |
    /// | BT709 full        | 8.05     | 33    |
    /// | BT709 limited     | 3.45     | 22    |
    ///
    /// Production decodes this BT.709 file with BT.601 coefficients: OpenCV's
    /// `VideoCapture` does not pass the container's colour space to swscale,
    /// which falls back to BT.601. The file's own metadata is therefore the
    /// wrong guide, and matching the cloud's IMPLEMENTATION beats matching the
    /// standard it claims to follow - which is the whole job of a parity port.
    ///
    /// Keep the default at `bt601Limited` for as long as the cloud decodes with
    /// OpenCV. If that ever changes to something colour-aware, this becomes
    /// wrong in the same quiet way.
    struct YuvMatrix {
        let kr: Double
        let kb: Double
        /// True for studio swing, where Y is 16...235 and chroma 16...240.
        let limitedRange: Bool

        static let bt601Full = YuvMatrix(kr: 0.299, kb: 0.114, limitedRange: false)
        static let bt601Limited = YuvMatrix(kr: 0.299, kb: 0.114, limitedRange: true)
        static let bt709Full = YuvMatrix(kr: 0.2126, kb: 0.0722, limitedRange: false)
        static let bt709Limited = YuvMatrix(kr: 0.2126, kb: 0.0722, limitedRange: true)
    }

    /// The coefficients a matrix implies, derived rather than hardcoded so the
    /// four combinations are one parameter apart.
    private struct Coefficients {
        let rv, bu, gu, gv: Double
        let yScale, yOffset, cScale: Double

        init(_ matrix: YuvMatrix) {
            let kr = matrix.kr, kb = matrix.kb
            let kg = 1.0 - kr - kb
            rv = 2.0 * (1.0 - kr)
            bu = 2.0 * (1.0 - kb)
            gu = -2.0 * (1.0 - kb) * kb / kg
            gv = -2.0 * (1.0 - kr) * kr / kg
            // Studio swing expands 16...235 luma and 16...240 chroma to 0...255.
            yScale = matrix.limitedRange ? 255.0 / 219.0 : 1.0
            yOffset = matrix.limitedRange ? 16.0 : 0.0
            cScale = matrix.limitedRange ? 255.0 / 224.0 : 1.0
        }
    }

    /// A locked biplanar buffer's planes, so the sampling loop reads pointers
    /// rather than calling into CoreVideo per pixel.
    ///
    /// The caller must have locked the buffer and must keep it locked for as
    /// long as this lives: every field here is a pointer into it.
    private struct Planes {
        let luma: UnsafePointer<UInt8>
        let chroma: UnsafePointer<UInt8>
        let lumaStride: Int
        let chromaStride: Int
        let width: Int
        let height: Int

        init?(_ buffer: CVPixelBuffer) {
            guard CVPixelBufferGetPlaneCount(buffer) == 2,
                  let y = CVPixelBufferGetBaseAddressOfPlane(buffer, 0),
                  let c = CVPixelBufferGetBaseAddressOfPlane(buffer, 1)
            else { return nil }
            luma = UnsafePointer(y.assumingMemoryBound(to: UInt8.self))
            chroma = UnsafePointer(c.assumingMemoryBound(to: UInt8.self))
            lumaStride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 0)
            chromaStride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 1)
            width = CVPixelBufferGetWidth(buffer)
            height = CVPixelBufferGetHeight(buffer)
        }

        /// One source pixel as RGB, unclamped and unrounded.
        ///
        /// Cb and Cr are interleaved two bytes apart in the same plane, which
        /// is the whole of the difference from Android's three-plane version.
        @inline(__always)
        func sample(x: Int, y: Int, _ k: Coefficients, into rgb: inout (Double, Double, Double)) {
            let yy = (Double(luma[y * lumaStride + x]) - k.yOffset) * k.yScale
            let chromaRow = (y / 2) * chromaStride + (x / 2) * 2
            let uu = (Double(chroma[chromaRow]) - 128) * k.cScale
            let vv = (Double(chroma[chromaRow + 1]) - 128) * k.cScale
            rgb.0 = yy + k.rv * vv
            rgb.1 = yy + k.gu * uu + k.gv * vv
            rgb.2 = yy + k.bu * uu
        }
    }

    // MARK: - Conversion

    /// Convert and resize in one pass, sampling only the pixels the output
    /// needs.
    ///
    /// Converting the whole 1920x1080 frame and then resizing measured 262ms
    /// per frame on an S23 - the second largest cost in the pipeline after
    /// inference - and most of that work is thrown away: the bilinear resize
    /// reads four source pixels per output pixel, so a 512x288 target needs at
    /// most 590k of the 2.07M converted.
    ///
    /// Equivalent to convert-then-resize because the YUV to RGB transform is
    /// per-pixel and interpolation is linear, so the order does not matter. The
    /// one real difference is rounding: this interpolates in floating point and
    /// rounds once at the end, where the two-step version rounds to bytes
    /// first. That makes this marginally more accurate, not less.
    ///
    /// The resize itself uses **OpenCV's pixel-centre mapping**,
    /// `(dst + 0.5) * scale - 0.5`, not the naive `dst * scale`. That is the
    /// whole point of writing it out rather than calling vImage: the naive form
    /// is off by half a source pixel everywhere, on every frame feeding every
    /// model, which is exactly the quiet drift section 5.4 warns about.
    @discardableResult
    static func toRgbResized(
        _ buffer: CVPixelBuffer,
        into destination: inout [UInt8],
        width dstW: Int,
        height dstH: Int,
        matrix: YuvMatrix = .bt601Limited
    ) -> Bool {
        toRgbResizedInto(
            buffer, canvas: &destination, canvasWidth: dstW,
            offsetX: 0, offsetY: 0, fitWidth: dstW, fitHeight: dstH, matrix: matrix
        )
    }

    /// As `toRgbResized`, but writing a smaller image into a larger canvas at an
    /// offset and leaving the rest untouched.
    ///
    /// This is the letterbox both YOLO models need: Ultralytics scales to fit
    /// and pads the short axis rather than stretching to a square, because the
    /// model was trained that way and stretching moves every box.
    @discardableResult
    static func toRgbResizedInto(
        _ buffer: CVPixelBuffer,
        canvas: inout [UInt8],
        canvasWidth: Int,
        offsetX: Int,
        offsetY: Int,
        fitWidth: Int,
        fitHeight: Int,
        matrix: YuvMatrix = .bt601Limited
    ) -> Bool {
        guard let planes = Planes(buffer) else { return false }
        precondition(
            canvas.count >= canvasWidth * (offsetY + fitHeight) * 3,
            "canvas too small for the letterbox"
        )
        let k = Coefficients(matrix)
        let scaleX = Double(planes.width) / Double(fitWidth)
        let scaleY = Double(planes.height) / Double(fitHeight)

        canvas.withUnsafeMutableBufferPointer { out in
            var p00 = (0.0, 0.0, 0.0)
            var p01 = (0.0, 0.0, 0.0)
            var p10 = (0.0, 0.0, 0.0)
            var p11 = (0.0, 0.0, 0.0)
            for row in 0..<fitHeight {
                let fy = max((Double(row) + 0.5) * scaleY - 0.5, 0.0)
                let y0 = min(Int(fy), planes.height - 1)
                let y1 = min(y0 + 1, planes.height - 1)
                let wy = fy - Double(y0)
                var o = ((offsetY + row) * canvasWidth + offsetX) * 3
                for col in 0..<fitWidth {
                    let fx = max((Double(col) + 0.5) * scaleX - 0.5, 0.0)
                    let x0 = min(Int(fx), planes.width - 1)
                    let x1 = min(x0 + 1, planes.width - 1)
                    let wx = fx - Double(x0)
                    planes.sample(x: x0, y: y0, k, into: &p00)
                    planes.sample(x: x1, y: y0, k, into: &p01)
                    planes.sample(x: x0, y: y1, k, into: &p10)
                    planes.sample(x: x1, y: y1, k, into: &p11)
                    out[o] = clamp(interpolate(p00.0, p01.0, p10.0, p11.0, wx, wy))
                    out[o + 1] = clamp(interpolate(p00.1, p01.1, p10.1, p11.1, wx, wy))
                    out[o + 2] = clamp(interpolate(p00.2, p01.2, p10.2, p11.2, wx, wy))
                    o += 3
                }
            }
        }
        return true
    }

    @inline(__always)
    private static func interpolate(
        _ p00: Double, _ p01: Double, _ p10: Double, _ p11: Double,
        _ wx: Double, _ wy: Double
    ) -> Double {
        let top = p00 + (p01 - p00) * wx
        let bottom = p10 + (p11 - p10) * wx
        return top + (bottom - top) * wy
    }

    /// Bilinear resize of an interleaved RGB image, matching `cv2.resize`'s
    /// `INTER_LINEAR`.
    ///
    /// Used only by the background pass, which resizes already-converted bytes
    /// rather than a pixel buffer. The per-frame path never calls it: it fuses
    /// the resize into the conversion above.
    static func resize(
        _ source: [UInt8], width srcW: Int, height srcH: Int,
        into destination: inout [UInt8], width dstW: Int, height dstH: Int
    ) {
        let scaleX = Double(srcW) / Double(dstW)
        let scaleY = Double(srcH) / Double(dstH)
        source.withUnsafeBufferPointer { src in
            destination.withUnsafeMutableBufferPointer { dst in
                var o = 0
                for row in 0..<dstH {
                    let fy = max((Double(row) + 0.5) * scaleY - 0.5, 0.0)
                    let y0 = min(Int(fy), srcH - 1)
                    let y1 = min(y0 + 1, srcH - 1)
                    let wy = fy - Double(y0)
                    for col in 0..<dstW {
                        let fx = max((Double(col) + 0.5) * scaleX - 0.5, 0.0)
                        let x0 = min(Int(fx), srcW - 1)
                        let x1 = min(x0 + 1, srcW - 1)
                        let wx = fx - Double(x0)
                        for c in 0..<3 {
                            let p00 = Double(src[(y0 * srcW + x0) * 3 + c])
                            let p01 = Double(src[(y0 * srcW + x1) * 3 + c])
                            let p10 = Double(src[(y1 * srcW + x0) * 3 + c])
                            let p11 = Double(src[(y1 * srcW + x1) * 3 + c])
                            dst[o] = clamp(interpolate(p00, p01, p10, p11, wx, wy))
                            o += 1
                        }
                    }
                }
            }
        }
    }

    /// Full-resolution YUV to interleaved RGB, no resize.
    ///
    /// The background pass's first step: production computes its median over
    /// frames it has converted, and `medianBackground` in `:analysis` takes
    /// interleaved bytes.
    @discardableResult
    static func toRgb(
        _ buffer: CVPixelBuffer,
        into destination: inout [UInt8],
        matrix: YuvMatrix = .bt601Limited
    ) -> Bool {
        guard let planes = Planes(buffer) else { return false }
        precondition(destination.count >= planes.width * planes.height * 3, "output buffer too small")
        let k = Coefficients(matrix)
        destination.withUnsafeMutableBufferPointer { out in
            var rgb = (0.0, 0.0, 0.0)
            var o = 0
            for row in 0..<planes.height {
                for col in 0..<planes.width {
                    planes.sample(x: col, y: row, k, into: &rgb)
                    out[o] = clamp(rgb.0)
                    out[o + 1] = clamp(rgb.1)
                    out[o + 2] = clamp(rgb.2)
                    o += 3
                }
            }
        }
        return true
    }

    // MARK: - Tensors

    /// Interleaved RGB bytes to the CHW float tensor, scaled by 1/255, written
    /// at `offset`.
    ///
    /// TrackNet's input is one 27-plane buffer holding a background and eight
    /// frames, so each frame is written in place rather than into its own array
    /// and copied. At 5.6MB per sequence the copies would dominate.
    static func toChwTensor(
        _ rgb: [UInt8], width: Int, height: Int,
        into tensor: inout [Float], offset: Int = 0
    ) {
        let plane = width * height
        precondition(tensor.count >= offset + plane * 3, "tensor too small")
        rgb.withUnsafeBufferPointer { src in
            tensor.withUnsafeMutableBufferPointer { out in
                for i in 0..<plane {
                    out[offset + i] = Float(src[i * 3]) / 255
                    out[offset + plane + i] = Float(src[i * 3 + 1]) / 255
                    out[offset + 2 * plane + i] = Float(src[i * 3 + 2]) / 255
                }
            }
        }
    }

    /// Rounds half away from zero and clamps to a byte, as Android's `clamp`
    /// does. `UInt8(clamping:)` truncates instead, which biases every converted
    /// pixel down by up to one count.
    @inline(__always)
    private static func clamp(_ value: Double) -> UInt8 {
        let rounded = Int(value + 0.5)
        if rounded < 0 { return 0 }
        if rounded > 255 { return 255 }
        return UInt8(rounded)
    }
}

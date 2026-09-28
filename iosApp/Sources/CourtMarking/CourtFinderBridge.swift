import CoreGraphics
import Foundation
import OSLog
import Shared

/// The court finder (`:analysis`' `CourtFinder`) on the frame the marking
/// screen shows: Android's `DefaultCourtFinderPort`, for a `CGImage`.
///
/// The frame is drawn into an RGBA buffer at its own pixel size, the source
/// resolution the marks are in, and handed to Kotlin by pointer
/// (`BulkInterop.rgbImage`): a 1080p frame is two million pixels, which the
/// bridge could not carry one boxed element at a time. The pixel copy and the
/// search both run on a detached task, off the main actor: a search takes a
/// fraction of a second on a desktop and longer on a phone.
///
/// Never throws. A frame that cannot be read comes back as not found, with the
/// reason, and the screen carries on as it does for a frame with no court: a
/// finder that fails must leave the coach able to mark by hand. The finder
/// itself turns its own failures into a not-found reason starting "error".
///
/// Every answer is logged, for tuning the finder on real videos.
enum CourtFinderBridge {

    /// Looks for the court on `image`.
    static func find(_ image: CGImage) async -> CourtFinding {
        await run("find", image) { CourtFinder.shared.find(image: $0) }
    }

    /// Completes the court from the four `corners` the coach tapped (TL, TR,
    /// BR, BL), in `image`'s pixels.
    static func fromCorners(_ image: CGImage, corners: [CourtPoint]) async -> CourtFinding {
        let points = corners.map { Point(x: Double($0.x), y: Double($0.y)) }
        return await run("fromCorners", image) { CourtFinder.shared.fromCorners(image: $0, corners: points) }
    }

    /// `image`'s pixels as the finder reads them: one ARGB int per pixel, top
    /// row first, at the image's own size.
    ///
    /// Drawn rather than read from the image's own data provider, because a
    /// decoded video frame may be in any byte order, row padding or colour
    /// space; the context fixes all three. Premultiplied alpha changes nothing
    /// on an opaque frame.
    static func rgbImage(_ image: CGImage) throws -> RgbImage {
        let width = image.width
        let height = image.height
        let bytesPerRow = width * 4
        var bytes = [UInt8](repeating: 0, count: bytesPerRow * height)
        return try bytes.withUnsafeMutableBytes { buffer in
            guard
                let base = buffer.baseAddress,
                let context = CGContext(
                    data: base, width: width, height: height, bitsPerComponent: 8, bytesPerRow: bytesPerRow,
                    space: CGColorSpaceCreateDeviceRGB(),
                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
                )
            else {
                throw CourtFinderBridgeError.unreadableFrame(width: width, height: height)
            }
            // A bitmap context's first row in memory is the top of the image,
            // which is the row order the finder's y-down pixels expect.
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return try BulkInterop.shared.rgbImage(
                pixels: base,
                width: Int32(width),
                height: Int32(height),
                bytesPerRow: Int32(bytesPerRow)
            )
        }
    }

    private static func run(
        _ call: String,
        _ image: CGImage,
        _ work: @escaping (RgbImage) -> CourtFinding
    ) async -> CourtFinding {
        let finding = await Task.detached(priority: .userInitiated) { () -> CourtFinding in
            do {
                return work(try rgbImage(image))
            } catch {
                return CourtFinding(
                    keypoints: nil, homography: nil,
                    reason: "error: couldn't read the frame: \(error.localizedDescription)",
                    residualPx: nil, millis: 0
                )
            }
        }.value
        log(call, finding)
        return finding
    }

    /// One line per answer, as Android's `LogcatCourtFinderLog` writes it.
    /// Notice level and public, like `LocalAnalysisRunner.systemLog`, so
    /// `log show` prints the numbers. A call that failed, rather than found
    /// nothing, is a warning.
    private static func log(_ call: String, _ finding: CourtFinding) {
        let residual = finding.residualPx.map { "\($0.doubleValue)" } ?? "null"
        let line = "\(call): reason=\(finding.reason) residualPx=\(residual) millis=\(finding.millis)"
        if finding.reason.hasPrefix("error") {
            logger.warning("\(line, privacy: .public)")
        } else {
            logger.notice("\(line, privacy: .public)")
        }
    }

    private static let logger = Logger(subsystem: "com.badmintontracker.ios", category: "CourtFinder")
}

enum CourtFinderBridgeError: LocalizedError {
    case unreadableFrame(width: Int, height: Int)

    var errorDescription: String? {
        switch self {
        case let .unreadableFrame(width, height):
            return "no \(width)x\(height) RGBA context for the frame"
        }
    }
}

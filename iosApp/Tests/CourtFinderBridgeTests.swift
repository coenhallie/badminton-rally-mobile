import CoreGraphics
import Shared
import XCTest
@testable import iosApp

/// The court finder reached from Swift the way the marking screen reaches it:
/// a `CGImage` drawn into an RGBA buffer, read by `BulkInterop.rgbImage`, and
/// searched off the main actor.
///
/// Only this bundle can check the Swift half: the bitmap context's byte order
/// and row order are decided here, and the Kotlin tests start from bytes that
/// are already laid out.
final class CourtFinderBridgeTests: XCTestCase {

    // MARK: - The pixels

    /// A 2x2 frame whose four pixels differ in every channel: the top row red
    /// then green, the bottom row blue then (10, 20, 30). Read top row first,
    /// as the finder's keypoints are y-down, and as ARGB.
    func test_the_frame_is_read_top_row_first_as_argb() throws {
        let bytes: [UInt8] = [
            255, 0, 0, 255, 0, 255, 0, 255,
            0, 0, 255, 255, 10, 20, 30, 255,
        ]
        let provider = try XCTUnwrap(CGDataProvider(data: Data(bytes) as CFData))
        let image = try XCTUnwrap(CGImage(
            width: 2, height: 2, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: 8,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
            provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent
        ))

        let rgb = try CourtFinderBridge.rgbImage(image)

        XCTAssertEqual(rgb.width, 2)
        XCTAssertEqual(rgb.height, 2)
        let argb = (0..<4).map { UInt32(bitPattern: rgb.argb.get(index: Int32($0))) }
        XCTAssertEqual(argb, [0xFFFF_0000, 0xFF00_FF00, 0xFF00_00FF, 0xFF0A_141E])
    }

    // MARK: - The shared rules the marking screen reads

    /// Continue is disabled while the finder works, from the shared predicate
    /// the view reads through SKIE.
    func test_continue_waits_while_the_finder_works() {
        XCTAssertFalse(CourtFinderStatus.finding.allowsContinue)
        XCTAssertFalse(CourtFinderStatus.aligning.allowsContinue)
        XCTAssertTrue(CourtFinderStatus.found.allowsContinue)
        XCTAssertTrue(CourtFinderStatus.idle.allowsContinue)
    }

    /// The notes a tap ends, by the shared rule the view applies after a tap
    /// that changes the points.
    func test_an_edit_ends_the_notes_it_makes_untrue() {
        let few = CourtMarkingState(
            videoWidth: 1920, videoHeight: 1080, points: [CourtPoint(x: 1, y: 2)],
            markedAtSeconds: nil, frameSeconds: nil, history: [], selected: nil
        )
        XCTAssertEqual(CourtFinderStatus.findFailed.afterEdit(marking: few), .idle)
        XCTAssertEqual(CourtFinderStatus.alignFailed.afterEdit(marking: few), .alignFailed)
        XCTAssertEqual(CourtFinderStatus.found.afterEdit(marking: few), .found)
    }

    // MARK: - The finder

    func test_a_grey_frame_is_not_found_and_not_an_error() async throws {
        let finding = await CourtFinderBridge.find(try Self.filled(width: 64, height: 64, grey: 128), call: "find")

        XCTAssertFalse(finding.found)
        XCTAssertFalse(finding.reason.hasPrefix("error"), "unexpected reason: \(finding.reason)")
    }

    func test_a_drawn_court_is_found_on_its_lines() async throws {
        let finding = await CourtFinderBridge.find(try Self.drawnCourt(), call: "find")

        XCTAssertEqual(finding.reason, "ok")
        try Self.assertOnTruth(finding)
    }

    /// The corners as a coach taps them, a few pixels off, completed to the
    /// twelve points: the path the screen takes after the fourth tap.
    func test_four_tapped_corners_complete_a_drawn_court() async throws {
        let offsets: [(Float, Float)] = [(6, -4), (-5, 7), (4, 5), (-7, -3)]
        let taps = (0..<4).map { i in
            CourtPoint(
                x: Float(Self.truth[i].x) + offsets[i].0,
                y: Float(Self.truth[i].y) + offsets[i].1
            )
        }

        let finding = await CourtFinderBridge.fromCorners(try Self.drawnCourt(), corners: taps)

        XCTAssertEqual(finding.reason, "ok")
        try Self.assertOnTruth(finding)
    }

    // MARK: - The drawn court

    /// `SyntheticCourt.camera(w = 1280, h = 720, f = 1000.0)` in `:analysis`'
    /// tests (`CourtFinderTest.cams[0]`), court metres to pixels, row-major:
    /// the scene the Kotlin tests find the court in. Printed by a throwaway
    /// jvmTest in `analysis/src/jvmTest/.../court/` running
    /// `println(SyntheticCourt.camera(w = 1280, h = 720, f = 1000.0).joinToString())`.
    private static let homography: [Double] = [
        -50.53324098099143, -30.484191576341743, 794.1263849920239,
        0.0, -0.27003713010538777, 246.9219517683682,
        0.0, -0.04763154933803398, 1.0,
    ]

    /// `SyntheticCourt.truth` for the same camera: the twelve keypoints in
    /// table order (TL, TR, BR, BL, NL, NR, SNL, SNR, SFL, SFR, CTN, CTF).
    /// Printed by the same throwaway test running
    /// `println(SyntheticCourt.truth(SyntheticCourt.camera(w = 1280, h = 720, f = 1000.0)).joinToString { "(${it.x}, ${it.y})" })`.
    private static let truth: [(x: Double, y: Double)] = [
        (485.8736150079762, 246.9219517683682),
        (794.1263849920239, 246.9219517683682),
        (1066.072763405115, 672.5972006220841),
        (213.92723659488547, 672.5972006220841),
        (413.63270887725406, 360.00000000000006),
        (866.3672911227462, 360.00000000000006),
        (441.17319010274855, 316.8911295394947),
        (838.8268098972515, 316.8911295394947),
        (377.23596793723993, 416.97149507014336),
        (902.7640320627604, 416.97149507014336),
        (640.0000000000001, 316.8911295394947),
        (640.0000000000001, 416.97149507014336),
    ]

    /// How far a found keypoint may sit from the truth, px: the bound the
    /// Kotlin tests hold their own scenes to. This one is found to 0.12 px at
    /// worst (measured 2026-09-28). A frame read upside down or mirrored
    /// would miss by far more, and the court drawn half a pixel off missed by
    /// 0.57 to 0.78 px.
    private static let tolerancePx = 0.5

    private static func assertOnTruth(_ finding: CourtFinding, file: StaticString = #filePath, line: UInt = #line) throws {
        let points = try XCTUnwrap(finding.toCourtPoints(), file: file, line: line)
        XCTAssertEqual(points.count, 12, file: file, line: line)
        for (i, p) in points.enumerated() {
            let off = hypot(Double(p.x) - truth[i].x, Double(p.y) - truth[i].y)
            XCTAssertLessThan(
                off, tolerancePx,
                "\(CourtMarkingSpec.shared.shortLabels[i]) is \(off) px off", file: file, line: line
            )
        }
    }

    /// The court's paint as `SyntheticCourt.render` lays it (40 mm lines, each
    /// inside the area it bounds), filled in a light grey on green through
    /// [homography]: the Kotlin tests' first colours, without their noise,
    /// blur and distractors.
    private static func drawnCourt() throws -> CGImage {
        let w = 6.1, l = 13.4, s = 1.98, ls = 0.76, o = (6.1 - 5.18) / 2, lw = 0.04
        var paint: [(x0: Double, x1: Double, y0: Double, y1: Double)] = []
        for y0 in [0, ls, l / 2 - s - lw, l / 2 + s, l - ls - lw, l - lw] {
            paint.append((0, w, y0, y0 + lw))
        }
        for x0 in [0, o, w - o - lw, w - lw] {
            paint.append((x0, x0 + lw, 0, l))
        }
        paint.append((w / 2 - lw / 2, w / 2 + lw / 2, 0, l / 2 - s))
        paint.append((w / 2 - lw / 2, w / 2 + lw / 2, l / 2 + s, l))

        let context = try bitmap(width: 1280, height: 720)
        context.setFillColor(red: 0x3C / 255.0, green: 0x8C / 255.0, blue: 0x46 / 255.0, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: 1280, height: 720))
        // Pixel coordinates are y-down; Core Graphics' are y-up. And the
        // finder's pixel (i, j) is centred on (i, j), where Core Graphics
        // centres it on (i + 0.5, j + 0.5): without the half-pixel shift the
        // whole court is drawn 0.7 px down and to the right of its truth.
        context.translateBy(x: 0, y: 720)
        context.scaleBy(x: 1, y: -1)
        context.translateBy(x: 0.5, y: 0.5)
        context.setFillColor(red: 0xEB / 255.0, green: 0xEB / 255.0, blue: 0xEB / 255.0, alpha: 1)
        for r in paint {
            let corners = [(r.x0, r.y0), (r.x1, r.y0), (r.x1, r.y1), (r.x0, r.y1)].map(project)
            context.addLines(between: corners)
            context.closePath()
            context.fillPath()
        }
        return try XCTUnwrap(context.makeImage())
    }

    private static func project(_ m: (Double, Double)) -> CGPoint {
        let h = homography
        let z = h[6] * m.0 + h[7] * m.1 + h[8]
        return CGPoint(x: (h[0] * m.0 + h[1] * m.1 + h[2]) / z, y: (h[3] * m.0 + h[4] * m.1 + h[5]) / z)
    }

    private static func filled(width: Int, height: Int, grey: CGFloat) throws -> CGImage {
        let context = try bitmap(width: width, height: height)
        context.setFillColor(red: grey / 255, green: grey / 255, blue: grey / 255, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        return try XCTUnwrap(context.makeImage())
    }

    private static func bitmap(width: Int, height: Int) throws -> CGContext {
        try XCTUnwrap(CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ))
    }
}

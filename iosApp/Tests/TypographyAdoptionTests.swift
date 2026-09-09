import XCTest
import UIKit
@testable import iosApp

/// Guards typeface consistency across the iOS app.
///
/// Android gets this for free: all 15 M3 Typography slots were put on Archivo
/// centrally, so every Compose screen inherits it. SwiftUI has no equivalent
/// central object, so on iOS it has to be adopted per call site and nothing but
/// a test stops the next new view from silently reverting to San Francisco.
///
/// This counts source call sites rather than rendering anything: a rendering
/// test would need a host app and would still only cover the screens it drove.
final class TypographyAdoptionTests: XCTestCase {

    /// Call sites deliberately left on a system font, with the reason. Anything
    /// NOT in here that still calls `.font(` is a regression.
    private let allowed: Set<String> = [
        "CourtMarkingView.swift",     // sizes text inside a scaled Canvas
        "SchematicCourtGuide.swift",  // same
        // Sizes an SF Symbol glyph, not text. SF Symbols take their height from
        // a font's point size, so drawing Android's 24-unit icon at a matching
        // height inside the 64pt disc has to say `.font(.system(size:))` - there
        // is no type-scale role for "an icon this tall", and putting one there
        // would make the glyph track the text scale it has nothing to do with.
        "ShuttlEmptyState.swift",
        // Same exemption, same reason: the transport's chevrons, frame-step
        // arrows and play glyph are SF Symbols, which take their height from a
        // font's point size. There is no type-scale role for "an icon this
        // tall", and binding one would make the glyphs track the text scale.
        "ShuttlTransportBar.swift",
        // Only one call site in this file is actually exempt: the main score
        // digit's size is computed from GeometryReader (`min(geo.size.height *
        // 0.40, geo.size.width * 0.75)`), a runtime value no static type-scale
        // role can express. Every other `.font(` call that used to live here
        // was ordinary UI text on a numerals-shaped exemption and has been
        // converted - see final-review.md's F-typescale.
        "ScoringView.swift",
        // The metric card's tick is an SF Symbol inside a 22pt disc, same
        // exemption as ShuttlEmptyState and the transport bar: SF Symbols take
        // their height from a font's point size, and binding the glyph to a
        // type-scale role would make a checkmark grow with the text scale while
        // the disc around it did not.
        "MetricSelector.swift",
    ]

    private func sourceFiles() -> [URL] {
        // Walks the checked-out sources next to the test bundle. Falls back to
        // skipping rather than failing if the layout is not as expected, so this
        // never fails for an unrelated reason on CI.
        let here = URL(fileURLWithPath: #filePath)
        let root = here.deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Sources")
        guard let e = FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil) else { return [] }
        return e.compactMap { $0 as? URL }.filter { $0.pathExtension == "swift" }
    }

    func testEveryViewUsesTheTypeScale() throws {
        let files = sourceFiles()
        try XCTSkipIf(files.isEmpty, "source tree not reachable from the test bundle")
        var offenders: [String] = []
        for url in files {
            let name = url.lastPathComponent
            if allowed.contains(name) || url.path.contains("/Theme/") { continue }
            let text = try String(contentsOf: url, encoding: .utf8)
            if text.contains(".font(") {
                offenders.append(name)
            }
        }
        XCTAssertEqual(
            offenders.sorted(), [],
            "these views still set a font directly instead of using .shuttlType(...): \(offenders.sorted())"
        )
    }
}

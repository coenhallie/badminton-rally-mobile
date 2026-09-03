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
        "ScoringView.swift",          // scoreboard numerals fill a half screen
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

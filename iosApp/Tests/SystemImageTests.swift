import UIKit
import XCTest
@testable import iosApp

/// Every SF Symbol the app names must actually resolve on the deployment target.
///
/// The failure this exists for is silent: `Image(systemName:)` with a name iOS
/// does not know renders nothing at all - no crash, no log, no placeholder - so a
/// mistyped or too-new symbol ships as a blank space where a control's only
/// affordance was. The same class of quiet nothing as a Swift file that never got
/// added to the Xcode project.
///
/// Walks the checked-out sources the way `TypographyAdoptionTests` does, rather
/// than keeping a hand-written list that a new call site would not be added to.
final class SystemImageTests: XCTestCase {

    /// `Image(systemName: "x")` and `Image(systemName: "x.fill")`, as written -
    /// only string literals, since a computed name cannot be checked from here.
    private func declaredSymbolNames() throws -> [(file: String, name: String)] {
        let here = URL(fileURLWithPath: #filePath)
        let sources = here.deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Sources")
        guard let walker = FileManager.default.enumerator(at: sources, includingPropertiesForKeys: nil) else {
            return []
        }
        let pattern = try NSRegularExpression(pattern: #"systemName:\s*"([^"]+)""#)
        var found: [(String, String)] = []
        for case let url as URL in walker where url.pathExtension == "swift" {
            let text = try String(contentsOf: url, encoding: .utf8)
            let range = NSRange(text.startIndex..., in: text)
            for match in pattern.matches(in: text, range: range) {
                guard let r = Range(match.range(at: 1), in: text) else { continue }
                found.append((url.lastPathComponent, String(text[r])))
            }
        }
        return found
    }

    func testEverySystemImageNameResolves() throws {
        let declared = try declaredSymbolNames()
        try XCTSkipIf(declared.isEmpty, "source tree not reachable from the test bundle")
        let missing = declared
            .filter { UIImage(systemName: $0.name) == nil }
            .map { "\($0.file): \($0.name)" }
        XCTAssertEqual(
            missing.sorted(), [],
            "these SF Symbol names resolve to nothing and render as blank space: \(missing.sorted())"
        )
    }
}

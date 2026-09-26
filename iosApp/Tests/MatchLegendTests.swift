import Shared
import XCTest
@testable import iosApp

/// `MatchLegendSheet.glyphKind(forLegendLabel:)`: every row of the shared
/// `MatchListCopy.LEGEND` must map to the glyph its own words describe, keyed
/// on the label rather than on the row's position - a positional pairing
/// would still compile and still run after the shared list changed shape.
final class MatchLegendTests: XCTestCase {
    private var expected: [String: LegendGlyphKind] {
        [
            MatchListCopy.shared.WHERE_PHONE: .wherePhone,
            MatchListCopy.shared.WHERE_CLOUD: .whereCloud,
            MatchListCopy.shared.ANALYSIS_RALLIES: .analysis(.rallies),
            MatchListCopy.shared.ANALYSIS_HEATMAP: .analysis(.heatmap),
            MatchListCopy.shared.ANALYSIS_SKELETON: .analysis(.skeleton),
            MatchListCopy.shared.ANALYSIS_SCORE: .analysis(.score),
        ]
    }

    func testEveryLegendEntryMapsToItsIntendedGlyph() {
        let legend = MatchListCopy.shared.LEGEND
        let expected = self.expected
        XCTAssertEqual(legend.count, expected.count, "a legend row was added or removed without a matching glyph mapping")
        for pair in legend {
            guard let label = pair.first as? String else { return XCTFail("a legend row has no label") }
            XCTAssertEqual(
                MatchLegendSheet.glyphKind(forLegendLabel: label), expected[label],
                "legend row \"\(label)\" is paired with the wrong glyph"
            )
        }
    }

    func testAnUnknownLabelMapsToNoGlyphRatherThanGuessing() {
        XCTAssertNil(MatchLegendSheet.glyphKind(forLegendLabel: "not a real legend row"))
    }

    func testOnlyTheWhereGlyphsAreBareTheAnalysesAreChipped() {
        XCTAssertTrue(LegendGlyphKind.wherePhone.isWhere)
        XCTAssertTrue(LegendGlyphKind.whereCloud.isWhere)
        XCTAssertFalse(LegendGlyphKind.analysis(.rallies).isWhere)
    }
}

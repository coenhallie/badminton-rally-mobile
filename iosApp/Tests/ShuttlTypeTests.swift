import XCTest
@testable import iosApp

/// The type scale's numbers, pinned against androidApp's ShuttlTypeTest.
///
/// Font objects are opaque, so what is asserted here is the scale table the
/// styles are built from. That is the part that drifts between platforms.
final class ShuttlTypeTests: XCTestCase {
    func testScaleMatchesTheDesign() {
        XCTAssertEqual(ShuttlType.display.size, 40)
        XCTAssertEqual(ShuttlType.display.weight, .medium)
        XCTAssertEqual(ShuttlType.display.trackingEm, -0.035, accuracy: 0.0001)

        XCTAssertEqual(ShuttlType.headlineLarge.size, 28)
        XCTAssertEqual(ShuttlType.headlineMedium.size, 22)
        XCTAssertEqual(ShuttlType.statNumber.size, 26)
        XCTAssertEqual(ShuttlType.titleLarge.size, 16)
        XCTAssertEqual(ShuttlType.titleMedium.size, 15)
        XCTAssertEqual(ShuttlType.bodyLarge.size, 16)
        XCTAssertEqual(ShuttlType.bodyMedium.size, 14)
        XCTAssertEqual(ShuttlType.bodySmall.size, 12)
        XCTAssertEqual(ShuttlType.labelSmall.size, 11)
        XCTAssertEqual(ShuttlType.labelSmall.trackingEm, 0.05, accuracy: 0.0001)
    }

    func testTrackingIsConvertedToPoints() {
        // -0.035em at 40pt is -1.4pt. The conversion is the thing that gets got
        // wrong when the scale is edited, so it is asserted rather than assumed.
        XCTAssertEqual(ShuttlType.display.kerning, -1.4, accuracy: 0.001)
        XCTAssertEqual(ShuttlType.labelSmall.kerning, 0.55, accuracy: 0.001)
    }

    func testEveryRoleAsksForAFontThatIsActuallyInTheBundle() {
        // Reads UIAppFonts rather than the Weight enum's own cases, which would
        // be a tautology. This catches the real failure: a role naming a weight
        // that was never added to project.yml.
        let declared = Bundle.main.object(forInfoDictionaryKey: "UIAppFonts") as? [String] ?? []
        XCTAssertFalse(declared.isEmpty, "UIAppFonts is missing from the built Info.plist")
        let bundledNames = Set(declared.map { ($0 as NSString).deletingPathExtension })
        for role in ShuttlType.allRoles {
            XCTAssertTrue(
                bundledNames.contains(role.weight.rawValue),
                "\(role.name) asks for \(role.weight.rawValue), which is not in UIAppFonts"
            )
        }
    }

    func testLineHeightsMatchAndroid() {
        // Stored on both platforms, so it is asserted on both. Android applies
        // it as `lineHeight`; what iOS does with it is documented on
        // `View.shuttlType(_:)`.
        XCTAssertEqual(ShuttlType.display.lineHeightMultiple, 1.08, accuracy: 0.001)
        XCTAssertEqual(ShuttlType.headlineLarge.lineHeightMultiple, 1.15, accuracy: 0.001)
        XCTAssertEqual(ShuttlType.headlineMedium.lineHeightMultiple, 1.20, accuracy: 0.001)
        XCTAssertEqual(ShuttlType.statNumber.lineHeightMultiple, 1.15, accuracy: 0.001)
        XCTAssertEqual(ShuttlType.titleLarge.lineHeightMultiple, 1.30, accuracy: 0.001)
        XCTAssertEqual(ShuttlType.titleMedium.lineHeightMultiple, 1.30, accuracy: 0.001)
        XCTAssertEqual(ShuttlType.bodyLarge.lineHeightMultiple, 1.45, accuracy: 0.001)
        XCTAssertEqual(ShuttlType.bodyMedium.lineHeightMultiple, 1.45, accuracy: 0.001)
        XCTAssertEqual(ShuttlType.bodySmall.lineHeightMultiple, 1.40, accuracy: 0.001)
        XCTAssertEqual(ShuttlType.labelSmall.lineHeightMultiple, 1.30, accuracy: 0.001)
    }
}

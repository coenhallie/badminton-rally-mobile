import XCTest
@testable import iosApp

/// The type scale's numbers, pinned against androidApp's ShuttlTypeTest.
///
/// Font objects are opaque, so what is asserted here is the scale table the
/// styles are built from. That is the part that drifts between platforms.
final class ShuttlTypeTests: XCTestCase {
    func testScaleMatchesTheDesign() {
        // Every number, for every role, pinned against androidApp's
        // ShuttlTypeTest.scale_matches_the_design. This is what makes the two
        // platforms' scale tables checkable role by role - the reason the
        // scale was split out of the built styles in the first place.
        struct Expected {
            let role: ShuttlType.Role
            let size: CGFloat
            let weight: ShuttlType.Weight
            let trackingEm: CGFloat
        }

        let expectations: [Expected] = [
            Expected(role: ShuttlType.display, size: 40, weight: .medium, trackingEm: -0.035),
            Expected(role: ShuttlType.headlineLarge, size: 28, weight: .medium, trackingEm: -0.030),
            Expected(role: ShuttlType.headlineMedium, size: 22, weight: .medium, trackingEm: -0.020),
            Expected(role: ShuttlType.statNumber, size: 26, weight: .medium, trackingEm: -0.030),
            Expected(role: ShuttlType.wordmark, size: 24, weight: .bold, trackingEm: -0.010),
            Expected(role: ShuttlType.titleLarge, size: 16, weight: .semibold, trackingEm: -0.010),
            Expected(role: ShuttlType.titleMedium, size: 15, weight: .semibold, trackingEm: -0.010),
            Expected(role: ShuttlType.labelMedium, size: 13, weight: .semibold, trackingEm: -0.010),
            Expected(role: ShuttlType.bodyLarge, size: 16, weight: .regular, trackingEm: 0),
            Expected(role: ShuttlType.bodyMedium, size: 14, weight: .regular, trackingEm: 0),
            Expected(role: ShuttlType.bodySmall, size: 12, weight: .regular, trackingEm: 0),
            Expected(role: ShuttlType.labelSmall, size: 11, weight: .medium, trackingEm: 0.05),
        ]

        for e in expectations {
            XCTAssertEqual(e.role.size, e.size, "\(e.role.name) size")
            XCTAssertEqual(e.role.weight, e.weight, "\(e.role.name) weight")
            XCTAssertEqual(e.role.trackingEm, e.trackingEm, accuracy: 0.0001, "\(e.role.name) trackingEm")
        }
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
        XCTAssertEqual(ShuttlType.wordmark.lineHeightMultiple, 1.20, accuracy: 0.001)
        XCTAssertEqual(ShuttlType.titleLarge.lineHeightMultiple, 1.30, accuracy: 0.001)
        XCTAssertEqual(ShuttlType.titleMedium.lineHeightMultiple, 1.30, accuracy: 0.001)
        XCTAssertEqual(ShuttlType.labelMedium.lineHeightMultiple, 1.30, accuracy: 0.001)
        XCTAssertEqual(ShuttlType.bodyLarge.lineHeightMultiple, 1.45, accuracy: 0.001)
        XCTAssertEqual(ShuttlType.bodyMedium.lineHeightMultiple, 1.45, accuracy: 0.001)
        XCTAssertEqual(ShuttlType.bodySmall.lineHeightMultiple, 1.40, accuracy: 0.001)
        XCTAssertEqual(ShuttlType.labelSmall.lineHeightMultiple, 1.30, accuracy: 0.001)
    }
}

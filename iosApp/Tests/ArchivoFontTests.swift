import XCTest
import UIKit
@testable import iosApp

/// Guards the bundling, which is invisible until it is not: a missing UIAppFonts
/// entry does not crash or warn, it silently falls back to the system font on
/// every screen at once.
final class ArchivoFontTests: XCTestCase {
    private let expected = [
        "Archivo-Regular",
        "Archivo-Medium",
        "Archivo-SemiBold",
        "Archivo-Bold",
    ]

    func testEveryArchivoWeightIsRegistered() {
        for name in expected {
            XCTAssertNotNil(
                UIFont(name: name, size: 16),
                "\(name) did not resolve. Check UIAppFonts in iosApp/project.yml and rerun xcodegen."
            )
        }
    }

    func testArchivoIsNotSilentlyFallingBackToSystem() {
        // UIFont(name:) returns nil for an unknown name rather than a fallback,
        // but a typo that happened to match another installed family would pass
        // the check above. Assert the family too.
        let font = UIFont(name: "Archivo-SemiBold", size: 16)
        XCTAssertEqual(font?.familyName, "Archivo")
    }
}

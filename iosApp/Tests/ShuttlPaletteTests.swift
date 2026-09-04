import XCTest
@testable import iosApp

/// The palette's accessibility rules, asserted rather than trusted. The mock is
/// dark-only, so the light values are a derivation and nothing but a test keeps
/// that derivation honest as the palette is edited.
final class ShuttlPaletteTests: XCTestCase {

    // MARK: WCAG 2.1 relative luminance and contrast

    private func channel(_ c: UInt32) -> Double {
        let v = Double(c) / 255
        return v <= 0.03928 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4)
    }

    private func luminance(_ rgb: UInt32) -> Double {
        0.2126 * channel((rgb >> 16) & 0xFF)
            + 0.7152 * channel((rgb >> 8) & 0xFF)
            + 0.0722 * channel(rgb & 0xFF)
    }

    private func contrast(_ a: UInt32, _ b: UInt32) -> Double {
        let (la, lb) = (luminance(a), luminance(b))
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    func testContrastHelperMatchesKnownValues() {
        // Black on white is the WCAG maximum.
        XCTAssertEqual(contrast(0x000000, 0xFFFFFF), 21.0, accuracy: 0.01)
        XCTAssertEqual(contrast(0xFFFFFF, 0xFFFFFF), 1.0, accuracy: 0.01)
    }

    // MARK: Rules

    /// Runs `body` once per theme, handing it a picker that pulls the right half
    /// out of a token's pair. Both themes get every rule; a light palette that
    /// only ever gets eyeballed in dark is how the derivation goes wrong.
    private func eachTheme(
        _ body: (_ name: String, _ pick: (ShuttlPalette.Pair) -> UInt32) -> Void
    ) {
        body("light") { $0.light }
        body("dark") { $0.dark }
    }

    func testBodyTextClearsAAOnEverySurface() {
        eachTheme { theme, pick in
            let backgrounds = [
                ("bg", pick(ShuttlPalette.bg)),
                ("bgSecondary", pick(ShuttlPalette.bgSecondary)),
                ("bgTertiary", pick(ShuttlPalette.bgTertiary)),
            ]
            let foregrounds = [
                ("text", pick(ShuttlPalette.text)),
                ("textHeading", pick(ShuttlPalette.textHeading)),
                ("textSecondary", pick(ShuttlPalette.textSecondary)),
                ("textTertiary", pick(ShuttlPalette.textTertiary)),
            ]
            for (fgName, fg) in foregrounds {
                for (bgName, bg) in backgrounds {
                    XCTAssertGreaterThanOrEqual(
                        contrast(fg, bg), 4.5,
                        "\(theme) \(fgName) on \(bgName) is below the 4.5:1 body threshold"
                    )
                }
            }
        }
    }

    func testMutedTextClearsLargeTextThresholdOnly() {
        eachTheme { theme, pick in
            let ratio = contrast(pick(ShuttlPalette.textMuted), pick(ShuttlPalette.bg))
            // Muted is the hero line and nothing else. It is allowed to sit below
            // the body threshold, which is precisely why it is a separate token
            // from textTertiary rather than the same grey used at two sizes.
            XCTAssertGreaterThanOrEqual(ratio, 3.0, "\(theme) textMuted fails large-text contrast")
            XCTAssertLessThan(ratio, 4.5, "\(theme) textMuted now clears the body threshold; fold it into textTertiary")

            // This next assertion looks backwards - it asserts a FAILURE - but
            // that is the honest constraint. textMuted was only ever measured
            // against `bg`, its one intended consumer (phase 2's hero line). On
            // `bgTertiary` it does not clear this same 3.0:1 large-text floor
            // (2.920:1 light / 2.939:1 dark). Pinning that failure means if
            // someone later "improves" textMuted until this goes green, they are
            // forced to notice they have changed which surfaces it is valid on,
            // rather than silently gaining a surface no one verified. Text on a
            // raised surface must use textTertiary, not textMuted.
            let raisedRatio = contrast(pick(ShuttlPalette.textMuted), pick(ShuttlPalette.bgTertiary))
            XCTAssertLessThan(raisedRatio, 3.0, "\(theme) textMuted now clears bgTertiary; textMuted may be safe on raised surfaces now, update the doc comment and this test to say so")
        }
    }

    func testAccentCarriesOnAccentText() {
        eachTheme { theme, pick in
            XCTAssertGreaterThanOrEqual(
                contrast(pick(ShuttlPalette.onAccent), pick(ShuttlPalette.accent)), 4.5,
                "\(theme) onAccent is unreadable on the accent fill"
            )
        }
    }

    func testErrorCarriesOnErrorText() {
        eachTheme { theme, pick in
            XCTAssertGreaterThanOrEqual(
                contrast(pick(ShuttlPalette.onError), pick(ShuttlPalette.error)), 4.5,
                "\(theme) onError is unreadable on the error fill"
            )
        }
    }

    func testAccentIsAFillColourNotATextColour() {
        // The rule this encodes: accent-coloured TEXT uses accentDark. If someone
        // brightens the light accent until it passes as text, this fails and they
        // have to decide deliberately rather than by accident.
        XCTAssertLessThan(
            contrast(ShuttlPalette.accent.light, ShuttlPalette.bg.light), 4.5,
            "light accent now passes as text; the fill-only rule needs revisiting"
        )
        eachTheme { theme, pick in
            XCTAssertGreaterThanOrEqual(
                contrast(pick(ShuttlPalette.accentDark), pick(ShuttlPalette.bg)), 4.5,
                "\(theme) accentDark must be readable as text"
            )
        }
    }

    func testDarkPaletteMatchesTheMock() {
        // The dark values are lifted from Rally Home.dc.html. Pinning them stops
        // a well-meaning tweak from silently drifting away from the design.
        XCTAssertEqual(ShuttlPalette.bg.dark, 0x0B0C0D)
        XCTAssertEqual(ShuttlPalette.accent.dark, 0x3EE27C)
        XCTAssertEqual(ShuttlPalette.onAccent.dark, 0x06210F)
        XCTAssertEqual(ShuttlPalette.textMuted.dark, 0x5D6462)
    }
}

import Foundation

/// Every design token as a raw ARGB-less RGB integer, light and dark.
///
/// Separate from `Shuttl`'s `Color` accessors on purpose: a `Color` cannot be
/// taken apart again on a device-independent basis, so contrast rules could not
/// be asserted from a unit test if these values only existed inside one. Mirrors
/// androidApp's ShuttlPalette.kt, which exists for the same reason.
///
/// Dark is lifted from the badminton-design mock. Light is a derivation, not a
/// recolour: the mock's `#3EE27C` is 1.6:1 on white and unusable there.
enum ShuttlPalette {
    typealias Pair = (light: UInt32, dark: UInt32)

    static let bg:              Pair = (0xFFFFFF, 0x0B0C0D)
    static let bgSecondary:     Pair = (0xF5F7F6, 0x121415)
    static let bgTertiary:      Pair = (0xEDF0EE, 0x161819)
    static let bgInput:         Pair = (0xEDF0EE, 0x0E0F10)
    static let border:          Pair = (0xE3E6E4, 0x1A1D1E)
    static let borderSecondary: Pair = (0xCED3D0, 0x22262A)
    static let textHeading:     Pair = (0x0B0C0D, 0xF2F4F3)
    static let text:            Pair = (0x16191A, 0xF2F4F3)
    static let textSecondary:   Pair = (0x545C58, 0x8D938F)
    static let textTertiary:    Pair = (0x5F6763, 0x7F8682)
    /// The hero line, and nothing else. Below the 4.5:1 body threshold by
    /// design; see ShuttlPaletteTests.testMutedTextClearsLargeTextThresholdOnly.
    ///
    /// Verified only against `bg`, its one intended consumer: 3.351:1 light /
    /// 3.230:1 dark. On `bgSecondary` it is 3.115:1 light / 3.048:1 dark, and
    /// on `bgTertiary` it drops to 2.920:1 light / 2.939:1 dark, below the
    /// 3.0:1 large-text floor it otherwise clears. Do not put it on a raised
    /// surface; use `textTertiary` there instead.
    static let textMuted:       Pair = (0x878E8A, 0x5D6462)
    /// A fill colour. Accent-coloured TEXT uses `accentDark`.
    static let accent:          Pair = (0x16A34A, 0x3EE27C)
    /// What sits on top of an `accent` fill, in both themes. The mock's own
    /// pattern: near-black on green, which is the pairing that survives the
    /// jump from a dark-only design into a light theme.
    static let onAccent:        Pair = (0x04240F, 0x06210F)
    static let accentDark:      Pair = (0x15803D, 0x22C55E)
    static let sideHome:        Pair = (0x15803D, 0x14532D)
    static let sideAway:        Pair = (0x1D4ED8, 0x1E3A8A)
    static let error:           Pair = (0xEF4444, 0xEF4444)
    /// What sits on top of an `error` fill, in both themes. `error` itself is
    /// theme-invariant, so one near-black value clears 4.5:1 in both: 5.075:1.
    /// White does not - 3.763:1 - which is the bug this token exists to fix.
    static let onError:         Pair = (0x200808, 0x200808)
    static let warning:         Pair = (0xF59E0B, 0xF59E0B)
    static let info:            Pair = (0x3B82F6, 0x3B82F6)
}

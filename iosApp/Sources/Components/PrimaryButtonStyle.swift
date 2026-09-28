import SwiftUI

/// The accent pill: a page's main action. Disabled dims the whole control to
/// 0.5, as `SecondaryPillButtonStyle` and Android's ShuttlButton do; it used to
/// ignore `isEnabled`, so a button disabled while its action ran looked ready
/// to take a second tap.
struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .shuttlType(ShuttlType.titleLarge)
            .foregroundStyle(Shuttl.onAccent)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(Shuttl.accent.opacity(configuration.isPressed ? 0.8 : 1))
            .clipShape(Capsule())
            .opacity(isEnabled ? 1 : 0.5)
    }
}

/// The same pill, in the secondary treatment: the page's surface behind a
/// hairline border, rather than the accent.
///
/// Android's `ShuttlButtonVariant.Secondary`, and it exists here for the same
/// reason it does there - two actions offered side by side, one of which is the
/// one most people want. Disabled dims the whole control rather than the label
/// alone, matching Android.
struct SecondaryPillButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .shuttlType(ShuttlType.titleLarge)
            .foregroundStyle(Shuttl.textHeading)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(Shuttl.bgSecondary.opacity(configuration.isPressed ? 0.8 : 1))
            .overlay(Capsule().stroke(Shuttl.border, lineWidth: 1))
            .clipShape(Capsule())
            .opacity(isEnabled ? 1 : 0.5)
    }
}

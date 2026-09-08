import SwiftUI

/// The small accent pill: androidApp's `ShuttlButton(compact = true)`, which
/// sets labelMedium text in a 12 by 6 inset. `PrimaryButtonStyle` is the
/// full-width one; this is for an action that sits inside content, such as an
/// empty state's own button, where a bar-wide pill would dominate the page.
struct CompactPillButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .shuttlType(ShuttlType.labelMedium)
            .foregroundStyle(Shuttl.onAccent)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Shuttl.accent.opacity(configuration.isPressed ? 0.8 : 1))
            .clipShape(Capsule())
            .opacity(isEnabled ? 1 : 0.5)
    }
}

import SwiftUI

/// The small pill: androidApp's `ShuttlButton(compact = true)`, which sets
/// labelMedium text in a 12 by 6 inset. `PrimaryButtonStyle` is the full-width
/// one; this is for an action that sits inside content, such as an empty
/// state's own button, where a bar-wide pill would dominate the page.
///
/// `secondary` is Android's `ShuttlButtonVariant.Secondary` at the compact
/// size, in `SecondaryPillButtonStyle`'s colours: the page's surface behind a
/// hairline border rather than the accent, for an action that is not the one
/// the screen exists for (a Retry beside a failed load).
///
/// `fullTarget` keeps the pill as drawn but makes the button
/// `ShuttlTarget.minimum` tall, so a tap just above or below the 29pt pill
/// still lands. For a pill that is a screen's main control; callers in a
/// tight row leave it off and carry their own target.
struct CompactPillButtonStyle: ButtonStyle {
    var secondary: Bool = false
    var fullTarget: Bool = false

    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        if fullTarget {
            pill(configuration)
                .frame(minHeight: ShuttlTarget.minimum)
                .contentShape(Rectangle())
        } else {
            pill(configuration)
        }
    }

    private func pill(_ configuration: Configuration) -> some View {
        configuration.label
            .shuttlType(ShuttlType.labelMedium)
            .foregroundStyle(secondary ? Shuttl.textHeading : Shuttl.onAccent)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background((secondary ? Shuttl.bgSecondary : Shuttl.accent).opacity(configuration.isPressed ? 0.8 : 1))
            .overlay {
                if secondary { Capsule().stroke(Shuttl.border, lineWidth: 1) }
            }
            .clipShape(Capsule())
            .opacity(isEnabled ? 1 : 0.5)
    }
}

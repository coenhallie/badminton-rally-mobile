import SwiftUI

/// The app's text field: an uppercase label above a rounded input box.
/// Mirrors androidApp's ShuttlOutlinedTextField measure for measure: the
/// `sectionLabel` role above (Android's FieldLabel, the same tracked
/// labelSmall), 6 between, then `bgInput` behind a 1pt `borderSecondary`
/// stroke that turns `accent` while focused, at `ShuttlRadius.small`, with
/// bodyLarge text inset 12 by 10.
///
/// The visible label is hidden from VoiceOver and handed to the field as its
/// own label instead, so the field is read as "Email, text field" in one stop
/// rather than a stray "EMAIL" followed by an unnamed field. No placeholder:
/// the label above already says what goes in, as it does on Android.
///
/// Keyboard, content type, submit label and focus are set by the caller on
/// this view, as on a plain `TextField`; they reach the field through the
/// environment. A disabled field dims to 0.6, as Android's does.
struct ShuttlTextField: View {
    let label: String
    @Binding var text: String
    var secure: Bool = false

    @Environment(\.isEnabled) private var isEnabled
    @FocusState private var isFocused: Bool

    init(_ label: String, text: Binding<String>, secure: Bool = false) {
        self.label = label
        self._text = text
        self.secure = secure
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Shuttl.sectionLabel(label)
                .accessibilityHidden(true)
            input
                // Set here as well as through the label view: with an empty
                // prompt, which keeps a placeholder from repeating the label
                // above, iOS leaves the field with no accessibility label.
                .accessibilityLabel(label)
                .shuttlType(ShuttlType.bodyLarge)
                .foregroundStyle(Shuttl.text)
                .tint(Shuttl.accent)
                .focused($isFocused)
                // bodyLarge's line (16 at 1.45), which Android's field is
                // laid out on. A single-line field here sits on Archivo's
                // tighter natural line instead and came out 6pt shorter.
                .frame(minHeight: Self.lineHeight)
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(Shuttl.bgInput, in: RoundedRectangle(cornerRadius: ShuttlRadius.small))
                .overlay(
                    RoundedRectangle(cornerRadius: ShuttlRadius.small)
                        .strokeBorder(isFocused ? Shuttl.accent : Shuttl.borderSecondary, lineWidth: 1)
                )
        }
        .opacity(isEnabled ? 1 : 0.6)
    }

    @ViewBuilder private var input: some View {
        if secure {
            SecureField(text: $text, prompt: Text("")) { Text(label) }
        } else {
            TextField(text: $text, prompt: Text("")) { Text(label) }
        }
    }

    private static var lineHeight: CGFloat {
        ShuttlType.bodyLarge.size * ShuttlType.bodyLarge.lineHeightMultiple
    }
}

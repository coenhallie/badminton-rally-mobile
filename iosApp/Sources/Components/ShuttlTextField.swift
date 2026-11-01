import SwiftUI

/// The app's text field: an uppercase label above a rounded input box.
/// Mirrors androidApp's ShuttlOutlinedTextField measure for measure: the
/// `sectionLabel` role above (Android's FieldLabel, the same tracked
/// labelSmall), 6 between, then `bgInput` behind a 1pt `textTertiary` stroke
/// that turns 2pt `accent` while focused, at `ShuttlRadius.small`, with
/// bodyLarge text inset 12 by 10.
///
/// The stroke is textTertiary rather than `borderSecondary` because an input's
/// boundary needs 3:1 against what it sits on (WCAG 1.4.11): borderSecondary
/// was 1.52:1 on the light page and 1.29:1 on the dark one, where the fill is
/// invisible too. textTertiary is 5.82 / 5.41 / 5.41:1 on bg / bgSecondary /
/// bgRaised in light, 5.25 / 4.96 / 4.34:1 in dark. `strokeBorder` draws
/// inside the shape, so the 2pt focus stroke moves neither text nor size.
///
/// The visible label is hidden from VoiceOver and handed to the field as its
/// own label instead, so the field is read as "Email, text field" in one stop
/// rather than a stray "EMAIL" followed by an unnamed field. No placeholder:
/// the label above already says what goes in, as it does on Android.
///
/// Keyboard, content type, submit label and focus are set by the caller on
/// this view, as on a plain `TextField`; they reach the field through the
/// environment. A disabled field dims to 0.6, as Android's does.
///
/// `maxLength`, as Android's, caps typing at that many characters and shows an
/// n/max counter at the label's right. `minLines` above 1 makes the field grow
/// from that many lines as it is typed into (a description), rather than one
/// fixed line.
struct ShuttlTextField: View {
    let label: String
    @Binding var text: String
    var secure: Bool = false
    var maxLength: Int? = nil
    var minLines: Int = 1

    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.displayScale) private var displayScale
    @FocusState private var isFocused: Bool
    /// bodyLarge's line (16 at 1.45), which Android's field is laid out on,
    /// scaled with Dynamic Type the way the role's font is (relative to body).
    @ScaledMetric(relativeTo: .body) private var lineHeight: CGFloat =
        ShuttlType.bodyLarge.size * ShuttlType.bodyLarge.lineHeightMultiple

    init(_ label: String, text: Binding<String>, secure: Bool = false, maxLength: Int? = nil, minLines: Int = 1) {
        self.label = label
        self._text = text
        self.secure = secure
        self.maxLength = maxLength
        self.minLines = minLines
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            header
            input
                // Set here as well as through the label view: with an empty
                // prompt, which keeps a placeholder from repeating the label
                // above, iOS leaves the field with no accessibility label.
                .accessibilityLabel(label)
                .shuttlType(ShuttlType.bodyLarge)
                .foregroundStyle(Shuttl.text)
                .tint(Shuttl.accent)
                .focused($isFocused)
                // A fixed line, not the field's own height: a single-line
                // field sits on Archivo's tighter natural line (6pt shorter
                // than Android's), and a SecureField's is shorter again than a
                // TextField's, so Email and Password drew at different
                // heights (1px at the default size, 5px at an accessibility
                // size). Rounded up to whole pixels so both boxes land on the
                // same pixel height wherever they sit.
                .frame(height: multiline ? nil : (lineHeight * displayScale).rounded(.up) / displayScale)
                // A description grows from `minLines` lines instead.
                .frame(minHeight: multiline ? lineHeight * CGFloat(minLines) : nil, alignment: .topLeading)
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                // The whole box focuses the field, as Android's does: the
                // text field itself only takes taps on its own line, so a tap
                // in the padding around it used to do nothing. The gesture is
                // on the fill behind the field, not on the field's own chain,
                // so by construction a tap on the text reaches the field
                // untouched (caret placement, double-tap select, the
                // long-press menu) and only a tap that misses the text lands
                // on the fill.
                .background {
                    RoundedRectangle(cornerRadius: ShuttlRadius.small)
                        .fill(Shuttl.bgInput)
                        .contentShape(RoundedRectangle(cornerRadius: ShuttlRadius.small))
                        .onTapGesture { isFocused = true }
                }
                .overlay(
                    RoundedRectangle(cornerRadius: ShuttlRadius.small)
                        .strokeBorder(
                            isFocused ? Shuttl.accent : Shuttl.textTertiary,
                            lineWidth: isFocused ? 2 : 1
                        )
                        // Drawn over the edge of the fill; taps go through
                        // to it.
                        .allowsHitTesting(false)
                )
        }
        .opacity(isEnabled ? 1 : 0.6)
        .onChange(of: text) { _, new in
            if let maxLength, new.count > maxLength { text = String(new.prefix(maxLength)) }
        }
    }

    private var multiline: Bool { minLines > 1 && !secure }

    /// The label, and the counter at its right when the field is capped. The
    /// counter is read by VoiceOver from the field itself, not from here.
    @ViewBuilder private var header: some View {
        if let maxLength {
            HStack(alignment: .firstTextBaseline) {
                Shuttl.sectionLabel(label)
                Spacer(minLength: 8)
                Text("\(text.count)/\(maxLength)")
                    .shuttlType(ShuttlType.labelSmall)
                    .foregroundStyle(Shuttl.textSecondary)
            }
            .accessibilityHidden(true)
        } else {
            Shuttl.sectionLabel(label)
                .accessibilityHidden(true)
        }
    }

    @ViewBuilder private var input: some View {
        if secure {
            SecureField(text: $text, prompt: Text("")) { Text(label) }
        } else if multiline {
            TextField(text: $text, prompt: Text(""), axis: .vertical) { Text(label) }
                .lineLimit(minLines...)
        } else {
            TextField(text: $text, prompt: Text("")) { Text(label) }
        }
    }
}

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
/// n/max counter at the label's right, which VoiceOver hears as the field's
/// hint. Characters are counted as shared counts them (`sharedLength`, UTF-16
/// units), so the counter and the cap agree with the rule that judges the save.
/// At an accessibility text size the counter takes a line of its own under
/// the label: beside it, a label in a narrow column ("Opponent (optional)" in
/// the court card) had less room than its longest word and broke inside it.
/// `minLines` above 1 makes the field grow
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
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
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
                .accessibilityHint(maxLength.map { "\(text.sharedLength) of \($0) characters" } ?? "")
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
            if let maxLength, new.sharedLength > maxLength { text = new.prefix(sharedLength: maxLength) }
        }
    }

    private var multiline: Bool { minLines > 1 && !secure }

    /// The label, and the counter at its right when the field is capped, or
    /// under it at an accessibility size (see the type's notes). Hidden here:
    /// VoiceOver hears the count as the field's hint (see `body`).
    @ViewBuilder private var header: some View {
        if let maxLength {
            let counter = Text("\(text.sharedLength)/\(maxLength)")
                .shuttlType(ShuttlType.labelSmall)
                .foregroundStyle(Shuttl.textSecondary)
                .fixedSize()
            Group {
                if dynamicTypeSize.isAccessibilitySize {
                    VStack(alignment: .trailing, spacing: 2) {
                        Shuttl.sectionLabel(label)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        counter
                    }
                } else {
                    HStack(alignment: .firstTextBaseline) {
                        Shuttl.sectionLabel(label)
                        Spacer(minLength: 8)
                        counter
                    }
                }
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

extension String {
    /// This string's length as shared measures it: Kotlin's `String.length`,
    /// UTF-16 units. An emoji is two, where `count` says one; every cap the
    /// shared rules check (a player's name, a match's name) is in these units.
    var sharedLength: Int { utf16.count }

    /// The longest run of whole characters from the start that comes to at
    /// most `units` in `sharedLength`. Never cuts inside a character, so a
    /// surrogate pair or a flag is kept whole or left out whole.
    func prefix(sharedLength units: Int) -> String {
        var used = 0
        var end = startIndex
        for character in self {
            let width = character.utf16.count
            if used + width > units { break }
            used += width
            end = index(after: end)
        }
        return String(self[..<end])
    }
}

extension View {
    /// Scrolls this view (a field inside `proxy`'s scroll view) up until its
    /// bottom edge clears the keyboard, while `focused`: when the keyboard
    /// comes up over it, or when it takes focus with the keyboard already up.
    ///
    /// For a field taller than a line. iOS scrolls a focused field only as far
    /// as its caret, so a three-line description near the foot of a sheet was
    /// left with its first line above the keyboard and the rest under it. Only
    /// when the keyboard covers part of it (or comes within `gap` of it), so a
    /// field already in view stays where it is; and then to `gap` above the
    /// keyboard, not flush with its suggestion bar.
    func revealedAboveKeyboard(focused: Bool, in proxy: ScrollViewProxy) -> some View {
        modifier(RevealedAboveKeyboard(focused: focused, proxy: proxy))
    }
}

private struct RevealedAboveKeyboard: ViewModifier {
    let focused: Bool
    let proxy: ScrollViewProxy

    /// The space left between the view and the keyboard.
    private let gap: CGFloat = 12

    @State private var id = UUID()
    /// This view's bottom edge, in the window's coordinates.
    @State private var bottom: CGFloat = 0
    /// The keyboard's top edge, in the screen's, while it is up.
    @State private var keyboardTop: CGFloat? = nil

    func body(content: Content) -> some View {
        content
            // The scroll target: the view and `gap` under it, drawn as
            // nothing and changing no layout.
            .background(Color.clear.id(id).padding(.bottom, -gap))
            .onGeometryChange(for: CGFloat.self, of: { $0.frame(in: .global).maxY }) { bottom = $0 }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardDidShowNotification)) { note in
                keyboardTop = (note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect)?.minY
                reveal()
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
                keyboardTop = nil
            }
            .onChange(of: focused) { _, _ in reveal() }
    }

    private func reveal() {
        // A sheet's window fills the screen on a phone, so the two
        // coordinate spaces agree.
        guard focused, let keyboardTop, bottom + gap > keyboardTop else { return }
        withAnimation { proxy.scrollTo(id, anchor: .bottom) }
    }
}

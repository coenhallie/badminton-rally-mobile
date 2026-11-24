import SwiftUI
import UIKit
import Shared

/// The invite code entry: eight big boxes in two groups of four, drawn over
/// one invisible text field. Mirrors androidApp's `InviteCodeField`.
///
/// One field rather than eight keeps paste (long-press anywhere on the boxes),
/// backspace and VoiceOver working the way they do on any field; VoiceOver
/// reads it as "Invite code" with its text. The field is a UITextField (see
/// `InviteCodeInput`) because SwiftUI's TextField on iOS 17 lets a tap park
/// the cursor mid-code, where the next character would land in the wrong box.
///
/// `text` may hold the code in any form ("K7M4-QP9R", "k7m4qp9r"); the view
/// writes it back as `formatInviteInput` formats it. `onSubmit` runs on the
/// keyboard's Go key, and by itself whenever a keystroke or paste completes a
/// valid code (see `inviteCodeEntry`), with the keyboard put away first.
struct InviteCodeField: View {
    @Binding var text: String
    var autoFocus: Bool = false
    let onSubmit: () -> Void

    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// statNumber's line plus room around it, grown with Dynamic Type and
    /// capped where eight boxes would no longer fit a phone's width sensibly.
    @ScaledMetric(relativeTo: .title) private var boxHeight: CGFloat = 60
    @State private var isFocused = false

    var body: some View {
        let copy = PlayerInviteCopy.shared
        let entry = InviteCodeFormatKt.inviteCodeEntry(raw: text)
        VStack(alignment: .leading, spacing: 6) {
            Shuttl.sectionLabel(copy.JOIN_CODE_LABEL)
                .accessibilityHidden(true)
            ZStack {
                InviteCodeInput(
                    text: Binding(get: { entry.chars }, set: { accept($0, current: entry) }),
                    isFocused: $isFocused,
                    label: copy.JOIN_CODE_LABEL,
                    onReturn: submit
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                boxes(entry)
                    // Taps and long-presses go through to the field beneath.
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
            .frame(height: min(boxHeight, 96))
            if entry.hasDisallowedChar {
                Text(copy.JOIN_CODE_CHAR_HINT)
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textSecondary)
                    .padding(.top, 2)
            }
        }
        .opacity(isEnabled ? 1 : 0.6)
        .onAppear { if autoFocus { isFocused = true } }
    }

    private func accept(_ chars: String, current: InviteCodeEntry) {
        let next = InviteCodeFormatKt.inviteCodeEntry(raw: chars)
        guard next.chars != current.chars else { return }
        text = InviteCodeFormatKt.formatInviteInput(raw: next.chars)
        if next.completeCode != nil { submit() }
    }

    private func submit() {
        isFocused = false
        onSubmit()
    }

    private func boxes(_ entry: InviteCodeEntry) -> some View {
        let cells = entry.cells
        let typed = entry.chars.count
        // The box the next character goes into; the last box while all are full.
        let active = isFocused && isEnabled ? min(typed, cells.count - 1) : -1
        return HStack(spacing: 6) {
            ForEach(0..<cells.count, id: \.self) { i in
                if i == cells.count / 2 {
                    RoundedRectangle(cornerRadius: 1)
                        .fill(Shuttl.textTertiary)
                        .frame(width: 10, height: 2)
                }
                box(cells[i], active: i == active, caret: i == active && typed < cells.count)
            }
        }
    }

    /// The same strokes as ShuttlTextField: 1pt textTertiary at rest (3:1
    /// against the page), 2pt accent where typing goes. A character no code
    /// contains turns its box and itself to the error colour; the character is
    /// large text, so the error red's 3.8:1 on white reads.
    private func box(_ cell: InviteCodeCell, active: Bool, caret: Bool) -> some View {
        let flagged = !cell.isAllowed
        return ZStack {
            RoundedRectangle(cornerRadius: ShuttlRadius.small)
                .fill(Shuttl.bgInput)
            RoundedRectangle(cornerRadius: ShuttlRadius.small)
                .strokeBorder(
                    flagged ? Shuttl.error : (active ? Shuttl.accent : Shuttl.textTertiary),
                    lineWidth: active || flagged ? 2 : 1
                )
            if caret {
                TimelineView(.periodic(from: .now, by: 0.5)) { context in
                    let on = reduceMotion
                        || Int(context.date.timeIntervalSinceReferenceDate * 2).isMultiple(of: 2)
                    RoundedRectangle(cornerRadius: 1)
                        .fill(Shuttl.accent)
                        .frame(width: 2, height: min(boxHeight, 96) * 0.46)
                        .opacity(on ? 1 : 0)
                }
            } else {
                Text(cell.text)
                    .shuttlType(ShuttlType.statNumber)
                    .foregroundStyle(flagged ? Shuttl.error : Shuttl.text)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// The invisible field behind the boxes: capitals, no autocorrect or
/// suggestions, Go to submit, and a cursor that always stays at the end.
private struct InviteCodeInput: UIViewRepresentable {
    @Binding var text: String
    @Binding var isFocused: Bool
    let label: String
    let onReturn: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> EndAnchoredTextField {
        let field = EndAnchoredTextField()
        field.autocapitalizationType = .allCharacters
        field.autocorrectionType = .no
        field.spellCheckingType = .no
        field.smartDashesType = .no
        field.smartQuotesType = .no
        field.smartInsertDeleteType = .no
        field.keyboardType = .asciiCapable
        field.returnKeyType = .go
        field.textColor = .clear
        field.tintColor = .clear
        field.backgroundColor = .clear
        field.borderStyle = .none
        field.accessibilityLabel = label
        field.delegate = context.coordinator
        field.addTarget(context.coordinator, action: #selector(Coordinator.editingChanged(_:)), for: .editingChanged)
        field.setContentHuggingPriority(.defaultLow, for: .horizontal)
        field.setContentHuggingPriority(.defaultLow, for: .vertical)
        field.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return field
    }

    func updateUIView(_ field: EndAnchoredTextField, context: Context) {
        context.coordinator.parent = self
        if field.text != text { field.text = text }
        field.isEnabled = context.environment.isEnabled
        field.wantsFocus = isFocused
        if isFocused, !field.isFirstResponder {
            DispatchQueue.main.async { field.claimFocusIfWanted() }
        } else if !isFocused, field.isFirstResponder {
            DispatchQueue.main.async { field.resignFirstResponder() }
        }
    }

    final class Coordinator: NSObject, UITextFieldDelegate {
        var parent: InviteCodeInput

        init(_ parent: InviteCodeInput) { self.parent = parent }

        @objc func editingChanged(_ field: UITextField) {
            // Clean here, not only upstream: when typing changes nothing
            // (a hyphen, a 9th character) SwiftUI sees no change and would
            // leave the stray character sitting invisibly in the field.
            let clean = InviteCodeFormatKt.inviteCodeEntry(raw: field.text ?? "").chars
            if field.text != clean { field.text = clean }
            parent.text = clean
        }

        func textFieldDidBeginEditing(_ textField: UITextField) {
            if !parent.isFocused { parent.isFocused = true }
        }

        func textFieldDidEndEditing(_ textField: UITextField) {
            if parent.isFocused { parent.isFocused = false }
        }

        func textFieldShouldReturn(_ textField: UITextField) -> Bool {
            parent.onReturn()
            return false
        }

        func textFieldDidChangeSelection(_ textField: UITextField) {
            let end = textField.endOfDocument
            guard let range = textField.selectedTextRange,
                  textField.compare(range.start, to: end) != .orderedSame
                    || textField.compare(range.end, to: end) != .orderedSame
            else { return }
            textField.selectedTextRange = textField.textRange(from: end, to: end)
        }
    }
}

/// A text field whose cursor is invisible and only offers Paste, and which
/// takes focus as soon as it is in a window if focus was asked for before.
private final class EndAnchoredTextField: UITextField {
    var wantsFocus = false

    func claimFocusIfWanted() {
        if wantsFocus, window != nil, !isFirstResponder { becomeFirstResponder() }
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        claimFocusIfWanted()
    }

    override func caretRect(for position: UITextPosition) -> CGRect { .zero }

    override func selectionRects(for range: UITextRange) -> [UITextSelectionRect] { [] }

    override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
        action == #selector(paste(_:)) && super.canPerformAction(action, withSender: sender)
    }
}

import SwiftUI
import Shared

/// Picks one label for a note and takes its body. Labels are made on the Labels
/// screen and nowhere else: a label carries a scope now (see `LabelUsage`), and
/// this sheet has no room to ask about one - so a label created here would have
/// its scope guessed on the owner's behalf. `labels` is already the clip-scoped
/// subset when this is presented.
struct AddAnnotationSheet: View {
    let labels: [AnnotationLabel]
    let onAdd: (AnnotationLabel?, String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var selected: AnnotationLabel? = nil
    @State private var body_ = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Add note")
                    .shuttlType(ShuttlType.headlineMedium)
                    .foregroundStyle(Shuttl.textHeading)
                    .accessibilityAddTraits(.isHeader)
                if !labels.isEmpty {
                    ChipFlow(spacing: 8) {
                        ForEach(labels, id: \.id) { label in
                            chip(label)
                        }
                    }
                }
                ShuttlTextField("Note (optional)", text: $body_, minLines: 3)
                HStack(spacing: 12) {
                    Button("Cancel") { dismiss() }
                        .buttonStyle(SecondaryPillButtonStyle())
                    Button("Add") {
                        onAdd(selected, body_)
                        dismiss()
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    .disabled(selected == nil && body_.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                .padding(.top, 8)
            }
            .padding(.horizontal, ShuttlGutter.page)
            .padding(.top, 24)
            .padding(.bottom, 16)
        }
        .scrollBounceBehavior(.basedOnSize)
        .scrollDismissesKeyboard(.interactively)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationBackground(Shuttl.bgSecondary)
    }

    private func chip(_ label: AnnotationLabel) -> some View {
        let isSelected = selected?.id == label.id
        return Button {
            selected = isSelected ? nil : label   // tapping selected chip deselects
        } label: {
            Text(label.name)
                .shuttlType(ShuttlType.labelMedium)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Capsule().fill(isSelected ? Shuttl.accent : Shuttl.bgTertiary))
                .foregroundStyle(isSelected ? Shuttl.onAccent : Shuttl.text)
                .frame(minHeight: ShuttlTarget.minimum)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

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
        VStack(alignment: .leading, spacing: 16) {
            Text("Add note")
                .shuttlType(ShuttlType.headlineMedium)
            ChipFlow(spacing: 8) {
                ForEach(labels, id: \.id) { label in
                    chip(label)
                }
            }
            TextField("Note (optional)", text: $body_, axis: .vertical)
                .padding(12)
                .background(Shuttl.bgInput)
            HStack {
                Spacer()
                Button("Cancel") { dismiss() }
                Button("Add") {
                    onAdd(selected, body_)
                    dismiss()
                }
                .disabled(selected == nil && body_.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(.horizontal, 24)
        .padding(.top, 24)
        .padding(.bottom, 16)
        .frame(maxHeight: .infinity, alignment: .top)
    }

    private func chip(_ label: AnnotationLabel) -> some View {
        let isSelected = selected?.id == label.id
        return Button {
            selected = isSelected ? nil : label   // tapping selected chip deselects
        } label: {
            Text(label.name)
                .shuttlType(ShuttlType.bodySmall)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(Capsule().fill(isSelected ? Shuttl.accent : Shuttl.bgTertiary))
                .foregroundStyle(isSelected ? Shuttl.onAccent : Shuttl.text)
        }
    }
}

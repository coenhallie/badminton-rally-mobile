import SwiftUI
import Shared

struct AddAnnotationSheet: View {
    let labels: [AnnotationLabel]
    let canCreateLabel: Bool
    let onCreateLabel: (String) -> Void
    let onAdd: (AnnotationLabel?, String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var selected: AnnotationLabel? = nil
    @State private var body_ = ""
    @State private var creating = false
    @State private var newName = ""
    @State private var seenIds: Set<String> = []

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Add note")
                .font(.title2.weight(.semibold))
            HStack(spacing: 8) {
                ForEach(labels, id: \.id) { label in
                    chip(label)
                }
                if canCreateLabel && !creating {
                    Button("+ New label") { creating = true }
                        .font(.footnote)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Capsule().fill(Shuttl.bgTertiary))
                        .foregroundStyle(Shuttl.text)
                }
            }
            if creating {
                TextField("Label name", text: $newName)
                    .submitLabel(.done)
                    .onSubmit {
                        onCreateLabel(newName)
                        newName = ""
                        creating = false
                    }
                    .padding(12)
                    .background(Shuttl.bgInput)
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
        .task { seenIds = Set(labels.map(\.id)) }
        .onChange(of: labels.map(\.id)) { _, ids in
            // A newly created label arrives through the same `labels` list this
            // sheet was handed - select it as it shows up so the user lands back
            // on their note with the label they just typed already applied.
            if let last = labels.last, !seenIds.contains(last.id) { selected = last }
            seenIds = Set(ids)
        }
    }

    private func chip(_ label: AnnotationLabel) -> some View {
        let isSelected = selected?.id == label.id
        return Button {
            selected = isSelected ? nil : label   // tapping selected chip deselects
        } label: {
            Text(label.name)
                .font(.footnote)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(Capsule().fill(isSelected ? Shuttl.accent : Shuttl.bgTertiary))
                .foregroundStyle(isSelected ? .black : Shuttl.text)
        }
    }
}

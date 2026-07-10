import SwiftUI
import Shared

struct AddAnnotationSheet: View {
    let onAdd: (AnnotationKind?, String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var kind: AnnotationKind? = nil
    @State private var body_ = ""

    private let kinds: [AnnotationKind] = [.goodShot, .forcedError, .unforcedError]

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Add annotation")
                .font(.title2.weight(.semibold))
            HStack(spacing: 8) {
                ForEach(kinds, id: \.self) { k in
                    chip(k)
                }
            }
            TextField("Note (optional)", text: $body_, axis: .vertical)
                .padding(12)
                .background(Shuttl.bgInput)
            HStack {
                Spacer()
                Button("Cancel") { dismiss() }
                Button("Add") {
                    onAdd(kind, body_)
                    dismiss()
                }
                .disabled(kind == nil && body_.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding(.horizontal, 24)
        .padding(.top, 24)
        .padding(.bottom, 16)
        .frame(maxHeight: .infinity, alignment: .top)
    }

    private func chip(_ k: AnnotationKind) -> some View {
        let selected = kind == k
        return Button {
            kind = selected ? nil : k   // tapping selected chip deselects
        } label: {
            Text(kindLabel(k))
                .font(.footnote)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(Capsule().fill(selected ? Shuttl.accent : Shuttl.bgTertiary))
                .foregroundStyle(selected ? .black : Shuttl.text)
        }
    }
}

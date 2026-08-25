import SwiftUI
import Shared

/// A pushed destination (never a sheet). Rows expand in place: tapping a
/// collapsed row reveals its name field and swatch grid, tapping it again
/// closes it. `model.expandedId` is the one piece of expansion state for the
/// whole list, and creating a new label shares it too, so at most one editor
/// is ever open on screen.
struct LabelsView: View {
    let rally: RallyApp
    @StateObject private var model: LabelsModel
    @State private var pendingDelete: AnnotationLabel? = nil
    @State private var creatingLabel = false

    init(rally: RallyApp) {
        self.rally = rally
        _model = StateObject(wrappedValue: LabelsModel(rally: rally))
    }

    var body: some View {
        List {
            if let errorMessage = model.errorMessage {
                ErrorBanner(message: errorMessage)
                    .listRowInsets(EdgeInsets())
                    .onTapGesture { model.errorMessage = nil }
            }
            if model.labels.isEmpty {
                Text("No labels yet")
                    .foregroundStyle(Shuttl.textSecondary)
            } else {
                ForEach(model.labels, id: \.id) { label in
                    LabelRow(
                        label: label,
                        expanded: model.expandedId == label.id,
                        onToggle: {
                            creatingLabel = false
                            model.expand(label.id)
                        },
                        onRename: { name in await model.rename(label.id, to: name) },
                        onRecolor: { color in Task { await model.recolor(label.id, to: color) } }
                    )
                    .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                        Button(role: .destructive) {
                            pendingDelete = label
                        } label: {
                            Label("Delete", systemImage: "trash")
                        }
                    }
                }
            }
            Section {
                NewLabelRow(
                    isOpen: creatingLabel,
                    existingColorKeys: model.labels.map(\.colorKey),
                    onOpen: {
                        model.expandedId = nil
                        creatingLabel = true
                    },
                    onCreate: { name, color in
                        creatingLabel = false
                        Task { await model.create(name, color: color) }
                    },
                    onCancel: { creatingLabel = false }
                )
            }
        }
        .listStyle(.plain)
        .navigationTitle("Labels")
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.start() }
        .confirmationDialog(
            "Delete label?",
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }
            ),
            titleVisibility: .visible,
            presenting: pendingDelete
        ) { label in
            Button("Delete", role: .destructive) {
                let id = label.id
                pendingDelete = nil
                Task { await model.delete(id) }
            }
            Button("Cancel", role: .cancel) { pendingDelete = nil }
        } message: { label in
            Text("\"\(label.name)\" leaves the picker. Notes already tagged with it keep their badge.")
        }
    }
}

/// A collapsed row is just a colour dot and a name: no card, no chevron, no
/// count. Expanded, it grows the name field and swatch grid from `LabelEditor`.
private struct LabelRow: View {
    let label: AnnotationLabel
    let expanded: Bool
    let onToggle: () -> Void
    let onRename: (String) async -> Bool
    let onRecolor: (LabelColor) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 12) {
                swatchDot(for: LabelColor.companion.from(key: label.colorKey))
                Text(label.name)
                    .font(.body)
                    .foregroundStyle(Shuttl.text)
                Spacer()
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
            .onTapGesture(perform: onToggle)

            if expanded {
                LabelEditor(label: label, onRename: onRename, onRecolor: onRecolor)
            }
        }
    }

    private func swatchDot(for swatch: LabelColor?) -> some View {
        Circle()
            .fill(swatch.map { Color(rgb: UInt32($0.background & 0xFFFFFF)) } ?? Shuttl.bgTertiary)
            .frame(width: 12, height: 12)
    }
}

/// The name field and swatch grid for one expanded row. A fresh instance is
/// created each time a row expands (the `if expanded` branch in `LabelRow`
/// mounts it new), so `name` seeds from `label.name` at construction with no
/// blank first frame to fill in afterward.
///
/// Renaming commits on the Done key (`.onSubmit`) *and* on focus loss, not
/// Done alone: tapping a swatch to recolour moves focus away from the text
/// field without ever triggering Done, and committing only on an explicit
/// action (as Android's first pass did) let exactly that edit vanish
/// silently. `lastCommitted` guards against sending the same rename twice,
/// since Done itself both fires `.onSubmit` and drops focus; a rejected
/// rename (a duplicate name is a normal, user-visible outcome here) rolls
/// `lastCommitted` back so retyping the same text after resolving the
/// collision is not silently treated as a no-op.
private struct LabelEditor: View {
    let label: AnnotationLabel
    let onRename: (String) async -> Bool
    let onRecolor: (LabelColor) -> Void

    @State private var name: String
    @State private var lastCommitted: String
    @FocusState private var focused: Bool
    @State private var wasFocused = false

    init(label: AnnotationLabel, onRename: @escaping (String) async -> Bool, onRecolor: @escaping (LabelColor) -> Void) {
        self.label = label
        self.onRename = onRename
        self.onRecolor = onRecolor
        _name = State(initialValue: label.name)
        _lastCommitted = State(initialValue: label.name)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            TextField("Name", text: $name)
                .submitLabel(.done)
                .padding(12)
                .background(Shuttl.bgInput)
                .focused($focused)
                .onSubmit(commit)
                .onChange(of: focused) { _, isFocused in
                    if isFocused {
                        wasFocused = true
                    } else if wasFocused {
                        commit()
                    }
                }
            SwatchGrid(selectedKey: label.colorKey, onSelect: onRecolor)
        }
        .padding(.bottom, 12)
    }

    private func commit() {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed != lastCommitted else { return }
        let previous = lastCommitted
        lastCommitted = trimmed
        Task {
            let succeeded = await onRename(trimmed)
            if !succeeded { lastCommitted = previous }
        }
    }
}

/// Deliberately a 10-swatch, 5-column grid, not a wrapping layout: with
/// exactly ten swatches a flow layout stripes 9 + 1 at common widths, leaving
/// one circle stranded on its own row.
private struct SwatchGrid: View {
    let selectedKey: String?
    let onSelect: (LabelColor) -> Void

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 12), count: 5)

    var body: some View {
        LazyVGrid(columns: columns, spacing: 12) {
            ForEach(LabelColor.companion.PALETTE, id: \.key) { swatch in
                Button {
                    onSelect(swatch)
                } label: {
                    Circle()
                        .fill(Color(rgb: UInt32(swatch.background & 0xFFFFFF)))
                        .frame(width: 28, height: 28)
                        .overlay(
                            Circle().stroke(
                                Shuttl.text,
                                lineWidth: swatch.key == selectedKey ? 2 : 0
                            )
                        )
                }
                .accessibilityLabel(swatch.key.capitalized)
            }
        }
    }
}

/// The colour picker lives here, at creation time, so choosing a colour for a
/// new label is one step rather than "create, then find it in the list to
/// recolour". Defaults to the next swatch not already in use by an existing
/// label, the same rule the shared repo uses for the sheet's name-only path.
private struct NewLabelRow: View {
    let isOpen: Bool
    let existingColorKeys: [String]
    let onOpen: () -> Void
    let onCreate: (String, LabelColor) -> Void
    let onCancel: () -> Void

    @State private var name = ""
    @State private var selected: LabelColor = LabelColor.green

    var body: some View {
        if isOpen {
            VStack(alignment: .leading, spacing: 12) {
                TextField("Name", text: $name)
                    .submitLabel(.done)
                    .padding(12)
                    .background(Shuttl.bgInput)
                    .onSubmit(commit)
                SwatchGrid(selectedKey: selected.key, onSelect: { selected = $0 })
                HStack {
                    Spacer()
                    Button("Cancel") {
                        name = ""
                        onCancel()
                    }
                    Button("Add", action: commit)
                        .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .task(id: existingColorKeys) {
                selected = AnnotationLabelsRepositoryImpl.companion.nextUnusedColor(takenKeys: existingColorKeys)
            }
        } else {
            Button("New label", action: onOpen)
        }
    }

    private func commit() {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let color = selected
        name = ""
        onCreate(trimmed, color)
    }
}

import SwiftUI
import Shared

/// A pushed destination (never a sheet). Rows expand in place: tapping a
/// collapsed row reveals its name field and swatch grid, tapping it again
/// closes it. `model.expanded` is the one piece of expansion state for the
/// whole screen, and the toolbar "+" (which opens a not-yet-created draft
/// row) shares it too, so at most one editor is ever open on screen.
struct LabelsView: View {
    let rally: RallyApp
    @StateObject private var model: LabelsModel
    @State private var pendingDelete: AnnotationLabel? = nil

    init(rally: RallyApp) {
        self.rally = rally
        _model = StateObject(wrappedValue: LabelsModel(rally: rally))
    }

    var body: some View {
        VStack(spacing: 0) {
            if let errorMessage = model.errorMessage {
                ErrorBanner(message: errorMessage)
                    .onTapGesture { model.errorMessage = nil }
            }
            if model.labels.isEmpty && model.expanded != .new {
                // With the in-list "add" row gone, an empty screen must teach
                // the action itself rather than leave a first-time user
                // staring at nothing but a small "+" in the corner.
                ContentUnavailableView {
                    Label("No labels", systemImage: "tag")
                } description: {
                    Text("Create a label to tag notes as you review a match.")
                } actions: {
                    Button("Add label") { model.startCreating() }
                }
            } else {
                List {
                    if model.expanded == .new {
                        DraftLabelEditor(
                            existingColorKeys: model.labels.map(\.colorKey),
                            onCreate: { name, color in Task { await model.create(name, color: color) } }
                        )
                    }
                    ForEach(model.labels, id: \.id) { label in
                        LabelRow(
                            label: label,
                            expanded: model.expanded == .existing(label.id),
                            onToggle: { model.expand(label.id) },
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
                .listStyle(.plain)
            }
        }
        .navigationTitle("Labels")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    model.startCreating()
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("Add label")
            }
        }
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

/// The name field and swatch grid shared by an expanded [LabelRow] and by
/// [DraftLabelEditor] - the same in-place editor either way. It owns its own
/// focus lifecycle: a rename or a create commits on the Done key
/// (`.onSubmit`) *and* on focus loss, not Done alone, since tapping a swatch
/// to recolour moves focus away from the text field without ever triggering
/// Done. `wasFocused` guards against the spurious "unfocused" event SwiftUI
/// can deliver before the user has touched the field, and against Done
/// itself firing both `.onSubmit` and a focus-loss event for the same commit.
private struct EditorFields: View {
    @Binding var name: String
    let selectedKey: String?
    let onSelectColor: (LabelColor) -> Void
    let onCommit: () -> Void

    @FocusState private var focused: Bool
    @State private var wasFocused = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            TextField("Name", text: $name)
                .submitLabel(.done)
                .padding(12)
                .background(Shuttl.bgInput)
                .focused($focused)
                .onSubmit(onCommit)
                .onChange(of: focused) { _, isFocused in
                    if isFocused {
                        wasFocused = true
                    } else if wasFocused {
                        onCommit()
                    }
                }
            SwatchGrid(selectedKey: selectedKey, onSelect: onSelectColor)
        }
        .padding(.bottom, 12)
    }
}

/// Renames an existing, already-persisted label. A fresh instance is created
/// each time a row expands (the `if expanded` branch in `LabelRow` mounts it
/// new), so `name` seeds from `label.name` at construction with no blank
/// first frame to fill in afterward. `lastCommitted` guards against sending
/// the same rename twice; a rejected rename (a duplicate name is a normal,
/// user-visible outcome here) rolls it back so retyping the same text after
/// resolving the collision is not silently treated as a no-op.
private struct LabelEditor: View {
    let label: AnnotationLabel
    let onRename: (String) async -> Bool
    let onRecolor: (LabelColor) -> Void

    @State private var name: String
    @State private var lastCommitted: String

    init(label: AnnotationLabel, onRename: @escaping (String) async -> Bool, onRecolor: @escaping (LabelColor) -> Void) {
        self.label = label
        self.onRename = onRename
        self.onRecolor = onRecolor
        _name = State(initialValue: label.name)
        _lastCommitted = State(initialValue: label.name)
    }

    var body: some View {
        EditorFields(name: $name, selectedKey: label.colorKey, onSelectColor: onRecolor, onCommit: commit)
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

/// The not-yet-created label row, opened by the toolbar "+" (or the empty
/// state's action button) and shown at the top of the list while
/// `LabelsModel.expanded` is `.new`. The colour picker lives here, at
/// creation time, so choosing a colour for a new label is one step rather
/// than "create, then find it in the list to recolour". Defaults to the next
/// swatch not already in use by an existing label, the same rule the shared
/// repo uses for the Add-note sheet's name-only path.
///
/// Name and colour are held locally, not persisted, until the name field
/// commits: there is no id yet to rename or recolour against, so unlike an
/// expanded [LabelRow] this row cannot write through on every swatch tap.
/// Once [onCreate] succeeds, `LabelsModel.create` moves the expansion target
/// to the new label's own id, and this view stops being shown - the *same*
/// [EditorFields] then keeps rendering, bound to the real row, inside
/// [LabelEditor].
private struct DraftLabelEditor: View {
    let existingColorKeys: [String]
    let onCreate: (String, LabelColor) -> Void

    @State private var name = ""
    @State private var selected: LabelColor = LabelColor.green

    var body: some View {
        EditorFields(name: $name, selectedKey: selected.key, onSelectColor: { selected = $0 }, onCommit: commit)
            .task(id: existingColorKeys) {
                selected = AnnotationLabelsRepositoryImpl.companion.nextUnusedColor(takenKeys: existingColorKeys)
            }
    }

    private func commit() {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        onCreate(trimmed, selected)
    }
}

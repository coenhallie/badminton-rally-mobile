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
                ShuttlEmptyState(
                    systemImage: "tag",
                    title: "No labels yet",
                    message: "Create a label to tag notes as you review a match."
                ) {
                    Button { model.startCreating() } label: {
                        HStack(spacing: 8) {
                            Image(systemName: "plus")
                            Text("Add label")
                        }
                    }
                    .buttonStyle(CompactPillButtonStyle())
                    .accessibilityLabel("Add label")
                }
            } else {
                List {
                    if model.expanded == .new {
                        DraftLabelEditor(
                            existingColorKeys: model.labels.map(\.colorKey),
                            onCreate: { name, color, usage in
                                await model.create(name, color: color, usage: usage)
                            }
                        )
                    }
                    ForEach(model.labels, id: \.id) { label in
                        LabelRow(
                            label: label,
                            expanded: model.expanded == .existing(label.id),
                            onToggle: { model.expand(label.id) },
                            onRename: { name in await model.rename(label.id, to: name) },
                            onRecolor: { color in Task { await model.recolor(label.id, to: color) } },
                            onSetUsage: { usage in Task { await model.setUsage(label.id, to: usage) } }
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
    let onSetUsage: (LabelUsage) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 12) {
                swatchDot(for: LabelColor.companion.from(key: label.colorKey))
                // A long name must yield to the trailing caption rather than
                // push it off the row or clip it. lineLimit(1) makes this
                // Text flexible in HStack's layout pass - it can shrink down
                // to its truncated minimum - while the caption below, which
                // takes no lineLimit, stays inflexible and always gets its
                // full intrinsic width first.
                Text(label.name)
                    .shuttlType(ShuttlType.bodyLarge)
                    .foregroundStyle(Shuttl.text)
                    .lineLimit(1)
                    .truncationMode(.tail)
                Spacer()
                // Only on rows that are not `both`, so the common case stays
                // quiet and the caption reads as an exception rather than as a
                // column. Without it the split is invisible from the list.
                if let caption = Self.scopeCaption(label.scope) {
                    Text(caption)
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.textSecondary)
                }
            }
            .padding(.vertical, 10)
            .contentShape(Rectangle())
            .onTapGesture(perform: onToggle)

            if expanded {
                LabelEditor(label: label, onRename: onRename, onRecolor: onRecolor, onSetUsage: onSetUsage)
            }
        }
    }

    private func swatchDot(for swatch: LabelColor?) -> some View {
        Circle()
            .fill(swatch.map { Color(rgb: UInt32($0.background & 0xFFFFFF)) } ?? Shuttl.bgTertiary)
            .frame(width: 12, height: 12)
    }

    private static func scopeCaption(_ scope: LabelUsage) -> String? {
        switch scope {
        case .both: return nil
        case .scoreboard: return "BOARD"
        case .clips: return "CLIPS"
        default: return nil
        }
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
    let selectedUsage: LabelUsage
    let onSelectUsage: (LabelUsage) -> Void
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
            // Where this label may be offered. Same control the new-match
            // screen uses for singles/doubles, so it reads as one app - but
            // that screen draws its Picker label through a Form Section
            // header, and this editor is not in a Form, so `Picker("Use", ...)`
            // alone renders no visible label under `.pickerStyle(.segmented)`.
            // The caption below fills that gap; VoiceOver already reads "Use"
            // from the Picker itself regardless, so this is purely the sighted
            // half of the same label.
            VStack(alignment: .leading, spacing: 4) {
                Text("Use")
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textSecondary)
                    .textCase(.uppercase)
                Picker("Use", selection: Binding(get: { selectedUsage }, set: onSelectUsage)) {
                    Text("Both").tag(LabelUsage.both)
                    Text("Scoreboard").tag(LabelUsage.scoreboard)
                    Text("Clips").tag(LabelUsage.clips)
                }
                .pickerStyle(.segmented)
            }
            SwatchGrid(selectedKey: selectedKey, onSelect: onSelectColor)
        }
        .padding(.bottom, 12)
    }
}

/// Renames an existing, already-persisted label. A fresh instance is created
/// each time a row expands (the `if expanded` branch in `LabelRow` mounts it
/// new), so `name` seeds from `label.name` at construction with no blank
/// first frame to fill in afterward. `commitGuard` guards against sending
/// the same rename twice; a rejected rename (a duplicate name is a normal,
/// user-visible outcome here) rolls it back so retyping the same text after
/// resolving the collision is not silently treated as a no-op.
private struct LabelEditor: View {
    let label: AnnotationLabel
    let onRename: (String) async -> Bool
    let onRecolor: (LabelColor) -> Void
    let onSetUsage: (LabelUsage) -> Void

    @State private var name: String
    @State private var commitGuard: CommitGuard

    init(
        label: AnnotationLabel,
        onRename: @escaping (String) async -> Bool,
        onRecolor: @escaping (LabelColor) -> Void,
        onSetUsage: @escaping (LabelUsage) -> Void
    ) {
        self.label = label
        self.onRename = onRename
        self.onRecolor = onRecolor
        self.onSetUsage = onSetUsage
        _name = State(initialValue: label.name)
        _commitGuard = State(initialValue: CommitGuard(lastCommitted: label.name))
    }

    var body: some View {
        EditorFields(
            name: $name,
            selectedKey: label.colorKey,
            onSelectColor: onRecolor,
            selectedUsage: label.scope,
            onSelectUsage: onSetUsage,
            onCommit: commit
        )
    }

    private func commit() {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let previous = commitGuard.lastCommitted
        guard commitGuard.begin(trimmed) else { return }
        Task {
            let succeeded = await onRename(trimmed)
            if !succeeded { commitGuard.failed(previous: previous) }
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
                        // The touch target reaches the 44pt minimum without the
                        // circle growing: 28pt of colour centred in 44pt of
                        // hit area, mirroring Android's 28dp-in-48dp box.
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                // Not cosmetic, and not optional. Inside a List row SwiftUI gives
                // an .automatic-style button cell-wide activation, so one tap ran
                // all ten actions in palette order and the last write (slate) won
                // - whichever swatch was actually touched. .plain restores a hit
                // region per button. See LabelSwatchGridUITests.
                .buttonStyle(.plain)
                .accessibilityLabel(swatch.key.capitalized)
                // Without this the ring is the only signal, so VoiceOver cannot
                // tell which swatch is current - and neither can a UI test.
                .accessibilityAddTraits(swatch.key == selectedKey ? [.isSelected] : [])
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
///
/// `commitGuard` mirrors [LabelEditor]'s: `EditorFields` fires `onCommit` on
/// both Done and the focus loss that follows the keyboard dismissing, so a
/// single Done press reaches `commit()` twice. Without the guard the second
/// call resubmits the same name as a second, phantom create, which the
/// repository's duplicate-name check correctly rejects - so the label is
/// created and an error is shown anyway. A rejected create (a genuine
/// duplicate name is a normal, user-visible outcome here) rolls the guard
/// back so retyping the same text after resolving the collision still
/// dispatches instead of being silently treated as a no-op.
private struct DraftLabelEditor: View {
    let existingColorKeys: [String]
    let onCreate: (String, LabelColor, LabelUsage) async -> Bool

    @State private var name = ""
    @State private var selected: LabelColor = LabelColor.green
    @State private var usage: LabelUsage = .both
    @State private var commitGuard = CommitGuard()

    var body: some View {
        EditorFields(
            name: $name,
            selectedKey: selected.key,
            onSelectColor: { selected = $0 },
            selectedUsage: usage,
            onSelectUsage: { usage = $0 },
            onCommit: commit
        )
        .task(id: existingColorKeys) {
            selected = AnnotationLabelsRepositoryImpl.companion.nextUnusedColor(takenKeys: existingColorKeys)
        }
    }

    private func commit() {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let previous = commitGuard.lastCommitted
        guard commitGuard.begin(trimmed) else { return }
        Task {
            let succeeded = await onCreate(trimmed, selected, usage)
            if !succeeded { commitGuard.failed(previous: previous) }
        }
    }
}

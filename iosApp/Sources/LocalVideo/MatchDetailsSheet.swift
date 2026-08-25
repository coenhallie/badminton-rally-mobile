import Shared
import SwiftUI

/// Name and describe a match before it is analyzed.
///
/// Both values ride along on the videos INSERT and the database grants no UPDATE
/// on either column, so this sheet is only reachable while the entry is still
/// LOCAL — see `LocalVideoStatus.canEditDetails`.
///
/// `autoOpened` only changes the dismiss label: straight after an import or a
/// recording the sheet is something to get past ("Skip"), while from the row menu
/// it is a deliberate edit ("Cancel"). Dismissing never discards the video, which
/// is already saved by the time this opens.
struct MatchDetailsSheet: View {
    let entry: LocalVideoEntry
    let autoOpened: Bool
    let onSave: (String, String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var description: String

    private let titleCap = Int(LocalVideoDetailsKt.MAX_MATCH_TITLE_LENGTH)
    private let descriptionCap = Int(LocalVideoDetailsKt.MAX_MATCH_DESCRIPTION_LENGTH)

    init(entry: LocalVideoEntry, autoOpened: Bool, onSave: @escaping (String, String) -> Void) {
        self.entry = entry
        self.autoOpened = autoOpened
        self.onSave = onSave
        _title = State(initialValue: entry.title ?? "")
        _description = State(initialValue: entry.description_ ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Match name", text: $title)
                        .onChange(of: title) { _, new in title = String(new.prefix(titleCap)) }
                } header: {
                    counterHeader("Match name", count: title.count, cap: titleCap)
                }
                Section {
                    TextEditor(text: $description)
                        .frame(minHeight: 96)
                        .onChange(of: description) { _, new in
                            description = String(new.prefix(descriptionCap))
                        }
                } header: {
                    counterHeader("Description", count: description.count, cap: descriptionCap)
                }
            }
            .navigationTitle("Match details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(autoOpened ? "Skip" : "Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        onSave(title, description)
                        dismiss()
                    }
                }
            }
        }
    }

    private func counterHeader(_ label: String, count: Int, cap: Int) -> some View {
        HStack {
            Text(label)
            Spacer()
            Text("\(count)/\(cap)")
        }
    }
}

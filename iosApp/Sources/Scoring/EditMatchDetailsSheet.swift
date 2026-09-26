import Shared
import SwiftUI

/// What the editor is opened with and what it hands back: the name a match was
/// given, who played and what kind of match it was. `description` is nil for a
/// record that has no such field (a score log), and the field's text, empty
/// included, for one that does (a video); the editor shows the field only when
/// it exists to write into. Mirrors Android's `MatchDetailsDraft`.
struct MatchDetailsDraft {
    var title: String
    var description: String?
    var format: MatchFormat?
    var homePlayers: [String]
    var awayPlayers: [String]

    /// A scored match's details.
    init(log: ScoreLog) {
        title = log.title
        description = nil
        format = log.format
        homePlayers = log.homePlayers
        awayPlayers = log.awayPlayers
    }

    /// A filmed match's details, every field the video carries: the description
    /// was typed at upload and this is the only place it can change since.
    init(video: MatchSummary) {
        title = video.title ?? ""
        description = video.description ?? ""
        format = video.format
        homePlayers = video.homePlayers
        awayPlayers = video.awayPlayers
    }

    init(title: String, description: String?, format: MatchFormat?, homePlayers: [String], awayPlayers: [String]) {
        self.title = title
        self.description = description
        self.format = format
        self.homePlayers = homePlayers
        self.awayPlayers = awayPlayers
    }
}

/// Everything about a match that the new match form deliberately does not ask for:
/// who played and what kind of match it was, plus the name it was given. All of it
/// is optional but the name, and none of it changes the score.
///
/// One editor for both kinds of match. A scored match keeps these on its score
/// log and a filmed one on its videos row, which is the caller's business: the
/// fields and the rules are the same, except for the name cap, which is the
/// record's own (`MAX_MATCH_TITLE` against `VIDEO_MATCH_TITLE_CAP`), and the
/// description, which only a video has.
///
/// Opened from the match page and the board. Every rule comes from
/// `matchDetailsProblem`, which Android's editor calls too, and the names are
/// normalised on save - so what is typed here is handed over as it stands.
struct EditMatchDetailsSheet: View {
    let titleCap: Int
    let onSave: (MatchDetailsDraft) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var description: String
    @State private var format: MatchFormat?
    /// Always two per side. The second is kept while the format is switched back
    /// and forth, so choosing singles by mistake does not destroy a typed partner.
    @State private var home: [String]
    @State private var away: [String]

    private let hasDescription: Bool
    private let nameCap = Int(MatchDetailsKt.MAX_PLAYER_NAME)
    private let descriptionCap = Int(LocalVideoDetailsKt.MAX_MATCH_DESCRIPTION_LENGTH)

    init(initial: MatchDetailsDraft, titleCap: Int, onSave: @escaping (MatchDetailsDraft) -> Void) {
        self.titleCap = titleCap
        self.onSave = onSave
        hasDescription = initial.description != nil
        _title = State(initialValue: initial.title)
        _description = State(initialValue: initial.description ?? "")
        _format = State(initialValue: initial.format)
        _home = State(initialValue: Self.twoSlots(initial.homePlayers))
        _away = State(initialValue: Self.twoSlots(initial.awayPlayers))
    }

    private static func twoSlots(_ names: [String]) -> [String] {
        Array((names + ["", ""]).prefix(2))
    }

    private var pairs: Bool { MatchDetailsKt.playersPerSide(format: format) == 2 }
    /// Only the fields on screen are judged and sent: a partner left over from a
    /// doubles match that is now singles is not stored, so it must not block Save.
    private var homePlayers: [String] { pairs ? home : [home[0]] }
    private var awayPlayers: [String] { pairs ? away : [away[0]] }
    private var problem: String? {
        MatchDetailsKt.matchDetailsProblem(
            title: title, homePlayers: homePlayers, awayPlayers: awayPlayers, titleCap: Int32(titleCap)
        )
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

                // Only a video carries one: a name that can be changed beside a
                // note that cannot would be a stuck field.
                if hasDescription {
                    Section {
                        TextEditor(text: $description)
                            .frame(minHeight: 80)
                            .onChange(of: description) { _, new in
                                description = String(new.prefix(descriptionCap))
                            }
                    } header: {
                        counterHeader("Description", count: description.count, cap: descriptionCap)
                    }
                }

                Section("Format") {
                    Picker("Format", selection: $format) {
                        Text("Not set").tag(MatchFormat?.none)
                        ForEach(MatchFormat.allCases, id: \.self) { Text($0.shortLabel).tag(MatchFormat?.some($0)) }
                    }
                    .pickerStyle(.segmented)
                }

                Section("Home") {
                    nameField(pairs ? "Home player 1" : "Home player", $home[0])
                    if pairs { nameField("Home player 2", $home[1]) }
                }

                Section("Away") {
                    nameField(pairs ? "Away player 1" : "Away player", $away[0])
                    if pairs { nameField("Away player 2", $away[1]) }
                }

                if let problem {
                    Section {
                        Text(problem).foregroundStyle(Shuttl.error).shuttlType(ShuttlType.bodySmall)
                    }
                }
            }
            .animation(.default, value: pairs)
            .navigationTitle("Match details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        onSave(MatchDetailsDraft(
                            title: title,
                            description: hasDescription ? description : nil,
                            format: format,
                            homePlayers: homePlayers,
                            awayPlayers: awayPlayers
                        ))
                        dismiss()
                    }
                    .disabled(problem != nil)
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

    private func nameField(_ label: String, _ text: Binding<String>) -> some View {
        TextField(label, text: text)
            .textInputAutocapitalization(.words)
            .autocorrectionDisabled()
            .onChange(of: text.wrappedValue) { _, new in text.wrappedValue = String(new.prefix(nameCap)) }
    }
}

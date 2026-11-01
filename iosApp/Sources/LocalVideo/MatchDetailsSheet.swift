import Shared
import SwiftUI

/// Who's playing, and the name and description, of a match only its local
/// entry speaks for: straight after a recording or an import, from the list
/// row's "Edit details", and from the match page of a video with no score log
/// and no clips yet.
///
/// The Who's playing section (`playersDraft`) comes first, above the name, and
/// shows on any of them; `playersEditable` false shows it read-only, during the
/// upload. `onSave` hands back what to save: the name, the description, the
/// draft's assignment and its near side (`EndsDraft.toAssignment`), so no
/// caller re-derives it. The name and description show only while
/// `detailsEditable`: both ride along on the videos INSERT and the database
/// grants no UPDATE on either column, so they can change only while the entry
/// is still LOCAL (see `LocalVideoStatus.canEditDetails`). Hidden, they are
/// handed back as they came. A live `matchPlayersProblem` line holds Save.
///
/// `autoOpened` only changes the dismiss label: straight after an import or a
/// recording the sheet is something to get past ("Skip"), while from the row
/// menu it is a deliberate edit ("Cancel"). Dismissing never discards the
/// video, which is already saved by the time this opens.
///
/// Drawn on bgDialog with the app's own fields rather than a grouped form: the
/// form's row fill is the pill track's tone, on which One player | Both
/// players would have no track. Mirrors androidApp's MatchDetailsSheet.kt.
struct MatchDetailsSheet: View {
    let detailsEditable: Bool
    let playersEditable: Bool
    let roster: [Player]
    let onAddPlayer: AddPlayer
    let autoOpened: Bool
    let onSave: (String, String, MatchPlayers, Side?) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var description: String
    @State private var draft: EndsDraft

    private let titleCap = Int(LocalVideoDetailsKt.MAX_MATCH_TITLE_LENGTH)
    private let descriptionCap = Int(LocalVideoDetailsKt.MAX_MATCH_DESCRIPTION_LENGTH)

    init(
        initialTitle: String?,
        initialDescription: String?,
        detailsEditable: Bool,
        playersDraft: EndsDraft,
        playersEditable: Bool,
        roster: [Player],
        onAddPlayer: @escaping AddPlayer,
        autoOpened: Bool,
        onSave: @escaping (String, String, MatchPlayers, Side?) -> Void
    ) {
        self.detailsEditable = detailsEditable
        self.playersEditable = playersEditable
        self.roster = roster
        self.onAddPlayer = onAddPlayer
        self.autoOpened = autoOpened
        self.onSave = onSave
        _title = State(initialValue: initialTitle ?? "")
        _description = State(initialValue: initialDescription ?? "")
        _draft = State(initialValue: playersDraft)
    }

    /// The sheet for an entry as it stands: its own players and end, its name
    /// and description while LOCAL, the section read-only mid-upload. For the
    /// two places that open it from the entry alone (after Record or Import,
    /// and the list row's menu).
    init(
        entry: LocalVideoEntry,
        roster: [Player],
        onAddPlayer: @escaping AddPlayer,
        autoOpened: Bool,
        onSave: @escaping (String, String, MatchPlayers, Side?) -> Void
    ) {
        self.init(
            initialTitle: entry.title,
            initialDescription: entry.description_,
            detailsEditable: LocalVideoStatus.canEditDetails(stage: entry.stage),
            playersDraft: PlayersDraftKt.endsDraft(
                players: entry.matchPlayers, nearSide: entry.nearSide, prefillPlayerId: nil
            ),
            playersEditable: SaveMatchPlayersKt.playersTarget(scoreLogId: nil, entry: entry, videoId: nil)
                != .uploading,
            roster: roster,
            onAddPlayer: onAddPlayer,
            autoOpened: autoOpened,
            onSave: onSave
        )
    }

    private var assignment: (players: MatchPlayers, nearSide: Side?) {
        let pair = draft.toAssignment()
        return (pair.first ?? MatchPlayers.companion.NONE, pair.second.map { $0 as Side })
    }

    private var problem: String? {
        playersEditable ? MatchPlayersKt.matchPlayersProblem(players: assignment.players, format: nil) : nil
    }

    private var canSave: Bool { (detailsEditable || playersEditable) && problem == nil }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    WhoIsPlayingSection(
                        draft: $draft, players: roster, onAddPlayer: onAddPlayer, uploading: !playersEditable
                    )
                    if detailsEditable {
                        ShuttlTextField("Match name", text: $title, maxLength: titleCap)
                        ShuttlTextField("Description", text: $description, maxLength: descriptionCap, minLines: 3)
                    }
                    if let problem {
                        Text(problem)
                            .shuttlType(ShuttlType.bodySmall)
                            .foregroundStyle(Shuttl.error)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 8)
                .padding(.bottom, 24)
            }
            .scrollDismissesKeyboard(.interactively)
            .background(Shuttl.bgDialog)
            .navigationTitle("Match details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(Shuttl.bgDialog, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(autoOpened ? "Skip" : "Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        let (players, nearSide) = assignment
                        onSave(title, description, players, nearSide)
                        dismiss()
                    }
                    .disabled(!canSave)
                }
            }
        }
        .presentationBackground(Shuttl.bgDialog)
    }
}

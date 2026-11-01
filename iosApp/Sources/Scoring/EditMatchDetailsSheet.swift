import Shared
import SwiftUI

/// What the editor hands back: the name a match was given, what kind of match it
/// was, who played and which side was nearest the camera. `description` is nil
/// for a record that has no such field (a score log), and the field's text,
/// empty included, for one that does (a video). `players` is what is saved,
/// from the shared draft (`DetailsPlayersDraft.toAssignment`): cut to the
/// format, a profile side with no names. Mirrors Android's `MatchDetailsDraft`.
struct MatchDetailsDraft {
    var title: String
    var description: String?
    var format: MatchFormat?
    var players: MatchPlayers
    var nearSide: Side?
}

/// Everything about a match that the new match form deliberately does not ask for:
/// who played and what kind of match it was, plus the name it was given. All of it
/// is optional but the name, and none of it changes the score.
///
/// One editor for both kinds of match. A scored match keeps these on its score
/// log and a filmed one on its videos row, which is the caller's business: the
/// fields and the rules are the same, except for the name cap, which is the
/// record's own (`MAX_MATCH_TITLE` against `VIDEO_MATCH_TITLE_CAP`), the
/// description, which only a video has (`initialDescription` nil for a log), and
/// who played (`initialPlayers`: `scoreLogPlayersDraft` or `videoPlayersDraft`).
///
/// Who played:
/// - A score log: Home and Away, each a picker of the players, "Someone else"
///   (the name field) and "Add player"; "Home was at the" Near | Far | Not set
///   when `showsEnds`, a video being attached.
/// - A match born as a video: the Who's playing section, by court end.
/// - Doubles or mixed, either kind: today's typed names, two a side, no pickers.
///   Doubles and Mixed are not offered while anyone on the match is a profile,
///   and a line says to remove the players first.
///
/// `uploading` is the video mid-upload: the ends row, or the whole section,
/// shows read-only with the sentence saying it can change once the upload
/// finishes.
///
/// Every rule comes from shared: `matchDetailsProblem` for the name and the
/// typed names, `matchPlayersProblem` for who played, and `DetailsPlayersDraft`
/// (with the SidesDraft and EndsDraft it holds) for what is saved, what is
/// offered and what a format switch carries. This only renders the draft and
/// hands taps back. `onSave` answers nil to close, or a sentence to stay open
/// and say it.
///
/// Drawn on bgDialog with the app's own fields rather than a grouped form, for
/// the reason `MatchDetailsSheet` gives. Opened from the match page and the
/// board. Mirrors androidApp's EditMatchDetailsDialog.kt.
struct EditMatchDetailsSheet: View {
    let titleCap: Int
    let roster: [Player]
    let onAddPlayer: AddPlayer
    let showsEnds: Bool
    let uploading: Bool
    let onSave: (MatchDetailsDraft) -> String?

    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var description: String
    @State private var format: MatchFormat?
    @State private var who: DetailsPlayersDraft
    /// What the last save said, shown until the draft changes.
    @State private var refusal: String? = nil

    private let hasDescription: Bool
    private let nameCap = Int(MatchDetailsKt.MAX_PLAYER_NAME)
    private let descriptionCap = Int(LocalVideoDetailsKt.MAX_MATCH_DESCRIPTION_LENGTH)

    init(
        initialTitle: String,
        initialDescription: String?,
        initialFormat: MatchFormat?,
        initialPlayers: DetailsPlayersDraft,
        titleCap: Int,
        roster: [Player],
        onAddPlayer: @escaping AddPlayer,
        showsEnds: Bool = false,
        uploading: Bool = false,
        onSave: @escaping (MatchDetailsDraft) -> String?
    ) {
        self.titleCap = titleCap
        self.roster = roster
        self.onAddPlayer = onAddPlayer
        self.showsEnds = showsEnds
        self.uploading = uploading
        self.onSave = onSave
        hasDescription = initialDescription != nil
        _title = State(initialValue: initialTitle)
        _description = State(initialValue: initialDescription ?? "")
        _format = State(initialValue: initialFormat)
        _who = State(initialValue: initialPlayers)
    }

    /// The format pills, in order: "Not set", then every format.
    private static let formats: [MatchFormat?] = [nil] + MatchFormat.allCases.map { Optional($0) }

    /// The format pills that take two players a side, by index.
    private static let pairsIndices: Set<Int> = Set(
        formats.indices.filter { MatchDetailsKt.playersPerSide(format: formats[$0]) == 2 }
    )

    /// The ends row's pills, in order: Near (Home nearest the camera), Far
    /// (Away nearest), Not set.
    private static let endOptions: [Side?] = [.home, .away, nil]

    private var pairs: Bool { MatchDetailsKt.playersPerSide(format: format) == 2 }

    private var assignment: (players: MatchPlayers, nearSide: Side?) {
        let pair = who.toAssignment(format: format)
        return (pair.first ?? MatchPlayers.companion.NONE, pair.second.map { $0 as Side })
    }

    private var offersPairs: Bool { who.offersPairs(format: format) }

    private var problem: String? {
        let players = assignment.players
        return MatchDetailsKt.matchDetailsProblem(
            title: title, homePlayers: players.homePlayers, awayPlayers: players.awayPlayers,
            titleCap: Int32(titleCap)
        ) ?? MatchPlayersKt.matchPlayersProblem(players: players, format: format)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    ShuttlTextField("Match name", text: $title, maxLength: titleCap)
                    // Only a video carries one: a name that can be changed
                    // beside a note that cannot would be a stuck field.
                    if hasDescription {
                        ShuttlTextField("Description", text: $description, maxLength: descriptionCap, minLines: 3)
                    }
                    formatRow
                    players
                    if !who.byEnds && showsEnds { endsRow }
                    if let shown = refusal ?? problem {
                        Text(shown)
                            .shuttlType(ShuttlType.bodySmall)
                            .foregroundStyle(Shuttl.error)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.horizontal, ShuttlGutter.page)
                .padding(.top, 8)
                .padding(.bottom, 24)
                .animation(.default, value: pairs)
                .animation(.default, value: who.sides.showsName(side: .home))
                .animation(.default, value: who.sides.showsName(side: .away))
            }
            .scrollDismissesKeyboard(.interactively)
            .background(Shuttl.bgDialog)
            .navigationTitle("Match details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(Shuttl.bgDialog, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", action: save)
                        .disabled(problem != nil)
                }
            }
        }
        .presentationBackground(Shuttl.bgDialog)
        .onChange(of: who) { _, _ in refusal = nil }
        .onChange(of: format) { _, _ in refusal = nil }
    }

    private var formatRow: some View {
        VStack(alignment: .leading, spacing: 6) {
            Shuttl.sectionLabel("Format")
                .accessibilityHidden(true)
            ShuttlPillTabs(
                labels: Self.formats.map { $0?.shortLabel ?? "Not set" },
                selectedIndex: Self.formats.firstIndex(of: format) ?? 0,
                onSelect: { index in
                    format = Self.formats[index]
                    who = who.withFormat(format: format)
                },
                accessibilityLabel: "Format",
                disabledIndices: offersPairs ? [] : Self.pairsIndices
            )
            if !offersPairs {
                PlayersNote(text: PlayersCopy.shared.DOUBLES_NOTE)
                    .padding(.top, 6)
            }
        }
    }

    @ViewBuilder
    private var players: some View {
        if pairs {
            // Today's names, two a side: no pickers outside singles. No heading
            // over them: each field already says its side.
            nameField("Home player 1", side: .home, index: 0)
            nameField("Home player 2", side: .home, index: 1)
            nameField("Away player 1", side: .away, index: 0)
            nameField("Away player 2", side: .away, index: 1)
        } else if who.byEnds {
            if let ends = who.ends {
                WhoIsPlayingSection(
                    draft: Binding(
                        get: { who.ends ?? ends },
                        set: { who = who.doCopy(byEnds: who.byEnds, sides: who.sides, ends: $0) }
                    ),
                    players: roster,
                    onAddPlayer: onAddPlayer,
                    uploading: uploading
                )
            }
        } else {
            sideRow(.home)
            sideRow(.away)
        }
    }

    /// One side of a scored match in singles: its label, a picker of the players
    /// (the other side's player left out), "Someone else" and "Add player", and
    /// today's name field while the side reads "Someone else"
    /// (`SidesDraft.showsName`).
    private func sideRow(_ side: Side) -> some View {
        let sides = who.sides
        let home = side == .home
        let copy = PlayersCopy.shared
        let label = home ? copy.SIDE_HOME : copy.SIDE_AWAY
        let otherId = home ? sides.awayPlayerId : sides.homePlayerId
        return VStack(alignment: .leading, spacing: 8) {
            Shuttl.sectionLabel(label)
                .accessibilityHidden(true)
            PlayerPicker(
                selectedId: home ? sides.homePlayerId : sides.awayPlayerId,
                players: roster.filter { $0.id != otherId },
                onPick: { setSides(who.sides.withPlayer(side: side, playerId: $0.id)) },
                onAddPlayer: onAddPlayer,
                accessibilityName: label,
                someoneElse: sides.showsName(side: side),
                onSomeoneElse: { setSides(who.sides.withSomeoneElse(side: side)) }
            )
            if sides.showsName(side: side) {
                nameField(home ? "Home player" : "Away player", side: side, index: 0)
                    .transition(.opacity)
            }
        }
    }

    /// "Home was at the" Near | Far | Not set: the end of the attached video.
    private var endsRow: some View {
        let copy = PlayersCopy.shared
        let sides = who.sides
        return VStack(alignment: .leading, spacing: 6) {
            Shuttl.sectionLabel(copy.HOME_WAS_AT_THE)
                .accessibilityHidden(true)
            ShuttlPillTabs(
                labels: Self.endOptions.map(Self.endLabel),
                selectedIndex: Self.endOptions.firstIndex(of: sides.nearSide) ?? 2,
                onSelect: { index in
                    let s = who.sides
                    setSides(s.doCopy(
                        homePlayerId: s.homePlayerId, awayPlayerId: s.awayPlayerId,
                        homeNames: s.homeNames, awayNames: s.awayNames,
                        nearSide: Self.endOptions[index],
                        homeSomeoneElse: s.homeSomeoneElse, awaySomeoneElse: s.awaySomeoneElse
                    ))
                },
                accessibilityLabel: copy.HOME_WAS_AT_THE,
                enabled: !uploading
            )
            if uploading {
                PlayersNote(text: copy.UPLOAD_IN_PROGRESS)
                    .padding(.top, 6)
            }
        }
    }

    private static func endLabel(_ nearSide: Side?) -> String {
        switch nearSide {
        case .home: return PlayersCopy.shared.NEAR
        case .away: return PlayersCopy.shared.FAR
        default: return PlayersCopy.shared.NEAR_FAR_NOT_SET
        }
    }

    private func setSides(_ sides: SidesDraft) {
        who = who.doCopy(byEnds: who.byEnds, sides: sides, ends: who.ends)
    }

    private func nameField(_ label: String, side: Side, index: Int) -> some View {
        ShuttlTextField(
            label,
            text: Binding(
                get: {
                    let names = side == .home ? who.sides.homeNames : who.sides.awayNames
                    return index < names.count ? names[index] : ""
                },
                set: { setSides(who.sides.withName(side: side, index: Int32(index), name: $0.prefix(sharedLength: nameCap))) }
            ),
            maxLength: nameCap
        )
        .textInputAutocapitalization(.words)
        .autocorrectionDisabled()
    }

    private func save() {
        let (players, nearSide) = assignment
        let message = onSave(MatchDetailsDraft(
            title: title,
            description: hasDescription ? description : nil,
            format: format,
            players: players,
            nearSide: nearSide
        ))
        if let message { refusal = message } else { dismiss() }
    }
}

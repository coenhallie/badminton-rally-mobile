import SwiftUI
import Shared

/// One player's page: the title row (avatar and name, the switcher), then
/// Profile (playing hand and three tiles) and Matches (the match list filtered
/// to this player, with its filters and sort).
///
/// The page's title is a row under the bar: the player's avatar and name as a
/// switcher, a menu of every player, where picking one replaces this page
/// (`onSwitchPlayer`, see `HomePath.toPlayer`). The bar holds only Back, the
/// background-work indicator and the overflow; see `TitleRow` for why the
/// name is not in it. The overflow renames the player, sets the playing
/// hand, or deletes the player; once they leave the list, by that delete or
/// any other way, the page asks to be closed (`onGone`, see `gone`).
///
/// The Add button here is for this player: HomeView's Add sheet starts a
/// match that has them at home (`onAdd`).
///
/// `model` and `listModel` are HomeView's one instance each, so the tiles,
/// the Matches tab and the Players row all read one list. The page is a list
/// screen for the root announcements: it draws the root toast, and reports
/// every sheet and dialog it has up (`onOverlayChange`), so the failed-run
/// dialog never takes one of them down.
///
/// Mirrors androidApp's PlayerScreen.kt, except that androidApp puts the
/// switcher in its top bar (Ruling 23).
struct PlayerView: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    let intake: LocalVideoIntake
    let playerId: String
    let model: PlayersModel
    let listModel: ClipListModel
    /// Another player picked in the title's menu: their page replaces this one.
    let onSwitchPlayer: (String) -> Void
    /// The player has left the list: see `gone`.
    let onGone: () -> Void
    let onMatchTap: (MatchRoute) -> Void
    let onCourtMarking: (CourtMarkingRoute) -> Void
    /// The Add sheet, for this player.
    let onAdd: () -> Void
    /// Any sheet or dialog over the page, the Matches tab's included.
    let onOverlayChange: (Bool) -> Void
    /// What the root says. Owned by HomeView.
    @Binding var toast: ToastMessage?

    /// Profile, or Matches.
    @State private var tab = Self.profileTab
    /// The player as last seen, so a page closing after a delete keeps its
    /// name on screen while it animates away rather than going blank.
    @State private var lastPlayer: Player? = nil
    @State private var renaming = false
    @State private var choosingHand = false
    @State private var confirmingDelete = false
    /// Any sheet or dialog the Matches tab's list has open.
    @State private var listOverlayOpen = false
    /// The Matches tab's filter and order, kept here so they outlive a trip
    /// to Profile, which takes the list down. See `MatchesList.filter`.
    @State private var matchesFilter: MatchListFilter = .all
    @State private var matchesSort: MatchListSort = .newest

    private static let profileTab = 0
    private static let matchesTab = 1

    /// Whether the player this page is for has gone: deleted here (the shared
    /// delete drops them from the list once the server has), or on another
    /// device and dropped by a refresh. `wasPresent` is whether the page has
    /// seen them; a page opened before the players have loaded waits for the
    /// first refresh to settle (`loaded`) before it concludes they are not
    /// coming. Mirrors androidApp's `playerPageGone`.
    static func gone(present: Bool, wasPresent: Bool, loaded: Bool) -> Bool {
        !present && (wasPresent || loaded)
    }

    private var player: Player? { model.players.first { $0.id == playerId } }

    private var isGone: Bool {
        Self.gone(present: player != nil, wasPresent: lastPlayer != nil, loaded: model.loaded)
    }

    private var overlayOpen: Bool {
        renaming || choosingHand || confirmingDelete || listOverlayOpen
    }

    var body: some View {
        let shown = player ?? lastPlayer
        // Worked out here, once per change of the list: the tiles and the
        // delete dialog's count read it.
        let summary = PlayerMatchesKt.playerSummary(list: listModel.matchList, playerId: playerId)
        return page(shown: shown, summary: summary)
            .background(Shuttl.bg)
            .navigationBarTitleDisplayMode(.inline)
            .shuttlNavigationBarBackground()
            .toolbar { toolbarContent }
            .overlay(alignment: .bottomTrailing) {
                if player != nil {
                    AddMatchButton(action: onAdd)
                        .padding(.trailing, 16)
                        .padding(.bottom, 16)
                }
            }
            // Above the Add button, as on Players. HomeView ends it when the
            // coach leaves the list screens; see `RootToasts.listsLeft`.
            .shuttlToast($toast, bottomInset: 88, endsWhenScreenGoes: false)
            // While this page is in front, the players list is followed here:
            // the Players screen's own loop stops under the push. The match
            // list's loops run from whichever tab is shown: Profile's own
            // `.task`, or the Matches tab's list.
            .task { await model.start() }
            .onChange(of: player, initial: true) { _, current in
                if let current { lastPlayer = current }
            }
            // Closed only while in front (`HomePath.closingPlayer`), so this
            // asks again whenever the page comes back into view.
            .onChange(of: isGone, initial: true) { _, gone in
                if gone { onGone() }
            }
            .onAppear { if isGone { onGone() } }
            // The dialogs act on the player as the list has them now, and
            // close with the page if the player goes.
            .onChange(of: player == nil) { _, missing in
                if missing {
                    renaming = false
                    choosingHand = false
                    confirmingDelete = false
                }
            }
            .modifier(Dialogs(
                player: player, matchCount: Int(summary.matchCount), model: model,
                renaming: $renaming, choosingHand: $choosingHand, confirmingDelete: $confirmingDelete,
                onDeleted: onGone
            ))
            .onChange(of: overlayOpen, initial: true) { _, open in onOverlayChange(open) }
            // Leaving the page takes its sheets with it, whatever was last said.
            .onDisappear { onOverlayChange(false) }
    }

    @ViewBuilder
    private func page(shown: Player?, summary: PlayerSummary) -> some View {
        if let shown {
            VStack(spacing: 0) {
                TitleRow(
                    current: shown,
                    players: model.players,
                    onPick: { picked in
                        if picked != shown.id { onSwitchPlayer(picked) }
                    }
                )
                .padding(.horizontal, ShuttlGutter.page)
                ShuttlPillTabs(
                    labels: [PlayersCopy.shared.TAB_PROFILE, PlayersCopy.shared.TAB_MATCHES],
                    selectedIndex: tab,
                    onSelect: { tab = $0 },
                    accessibilityLabel: PlayersCopy.shared.PAGE_SECTIONS,
                    setsCeiling: true
                )
                .padding(.horizontal, ShuttlGutter.page)
                .padding(.top, 8)
                // Only the tab shown is built. The list kept mounted and hidden
                // under Profile went on being read by VoiceOver (a hidden List
                // keeps its rows in the accessibility tree), so it goes, and
                // its filter and order are kept here instead. Its scroll
                // starts at the top again after a trip to Profile, where
                // androidApp keeps it.
                Group {
                    if tab == Self.matchesTab {
                        MatchesList(
                            rally: rally, analyze: analyze, intake: intake, model: listModel,
                            playersModel: model,
                            onMatchTap: onMatchTap, onCourtMarking: onCourtMarking,
                            // A player's list never draws the hero.
                            heroPaused: true,
                            onOverlayChange: { listOverlayOpen = $0 },
                            playerId: playerId,
                            // The last row scrolls clear of the Add button:
                            // see the Players screen's own foot for the 88.
                            bottomInset: 88,
                            filter: $matchesFilter,
                            sort: $matchesSort
                        )
                    } else {
                        ProfileTab(player: shown, summary: summary)
                            // The tiles read the match list; the Matches
                            // tab's list runs the same loops when it is shown.
                            .task { await listModel.start() }
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .pillLabelCeilingHost()
        } else {
            Color.clear
        }
    }

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        // First in the bar's trailing group, so it keeps its place as each
        // screen's own actions come and go. See `BackgroundWorkAction`.
        ToolbarItem(placement: .topBarTrailing) { BackgroundWorkAction() }
        if player != nil {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button(PlayersCopy.shared.MENU_RENAME) { renaming = true }
                    Button(PlayersCopy.shared.MENU_PLAYING_HAND) { choosingHand = true }
                    Button(PlayersCopy.shared.MENU_DELETE, role: .destructive) { confirmingDelete = true }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .accessibilityLabel(PlayersCopy.shared.PLAYER_OPTIONS)
            }
        }
    }
}

/// The rename and playing-hand sheets and the delete confirmation, apart from
/// the page's body so its modifier chain stays within what the type checker
/// settles in time.
private struct Dialogs: ViewModifier {
    let player: Player?
    let matchCount: Int
    let model: PlayersModel
    @Binding var renaming: Bool
    @Binding var choosingHand: Bool
    @Binding var confirmingDelete: Bool
    /// The server deleted the player: the page closes.
    let onDeleted: () -> Void

    func body(content: Content) -> some View {
        let copy = PlayersCopy.shared
        content
            .sheet(isPresented: $renaming) {
                if let player {
                    PlayerNameSheet(
                        title: copy.RENAME_DIALOG_TITLE,
                        initialName: player.name,
                        initialArm: player.racketArm,
                        showArm: false
                    ) { name, _ in
                        // A failure stays in the sheet with the name typed, as
                        // the add sheet's does.
                        await model.rename(id: player.id, name: name)
                    }
                }
            }
            .sheet(isPresented: $choosingHand) {
                if let player {
                    PlayingHandSheet(initialArm: player.racketArm) { arm in
                        // A failure is said on the root toast.
                        Task { await model.setRacketArm(id: player.id, arm: arm) }
                    }
                }
            }
            .confirmationDialog(
                player.map { copy.deleteTitle(name: $0.name) } ?? "",
                isPresented: $confirmingDelete,
                titleVisibility: .visible,
                presenting: player
            ) { player in
                Button(copy.DELETE_CONFIRM, role: .destructive) {
                    // The shared delete: the matches keep the name and lose the
                    // id. The page closes once the server has agreed; a refusal
                    // is said on the root toast and the page stays.
                    Task {
                        if await model.delete(player) { onDeleted() }
                    }
                }
                Button(copy.CANCEL, role: .cancel) {}
            } message: { _ in
                // Counted from the list the Matches tab shows, so the dialog
                // says exactly what the coach has just been looking at.
                Text(copy.deleteBody(matchCount: Int32(matchCount)))
            }
    }
}

/// The page's title, as a large title sits under a bar: the player's avatar
/// and name with a chevron, which opens a menu of every player (sorted by
/// name, as the repository publishes them), the current one ticked.
///
/// In the page rather than in the bar. A bar sizes its items from their ideal
/// width and decides from that whether to fold the others into "•••", and it
/// never offers an item more than that ideal back, so a name in the bar either
/// ran over Back, pushed the background-work indicator into "•••", or needed a
/// width worked out from the bar's metrics on one iOS version (probed on iOS
/// 26.5: task-13-report.md, fix round 1). Here the row is offered the page's
/// width like any other view: the avatar and chevron keep their size and the
/// name takes the rest, cut with an ellipsis, on every width and iOS version.
///
/// The whole row is the menu's button, at least 44pt tall. VoiceOver hears
/// the name as the page's heading and as a pop-up button (Ruling 21).
private struct TitleRow: View {
    let current: Player
    let players: [Player]
    let onPick: (String) -> Void

    var body: some View {
        Menu {
            // A picker inside the menu, so the current player carries the
            // system's own tick.
            Picker(PlayersCopy.shared.SWITCH_PLAYER, selection: Binding(
                get: { current.id },
                set: { onPick($0) }
            )) {
                ForEach(players, id: \.id) { player in
                    Text(player.name).tag(player.id)
                }
            }
        } label: {
            HStack(spacing: 12) {
                PlayerAvatar(name: current.name, size: PlayerAvatar.rowSize)
                HStack(spacing: 6) {
                    Text(current.name)
                        .shuttlType(ShuttlType.headlineMedium)
                        .foregroundStyle(Shuttl.textHeading)
                        .lineLimit(1)
                        .truncationMode(.tail)
                    Image(systemName: "chevron.down")
                        .shuttlType(ShuttlType.labelMedium)
                        .foregroundStyle(Shuttl.textSecondary)
                        // Never the one cut: the name gives way first.
                        .layoutPriority(1)
                }
            }
            .frame(minHeight: ShuttlTarget.minimum)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(current.name)
        .accessibilityHint(PlayersCopy.shared.SWITCH_PLAYER)
        .accessibilityAddTraits(.isHeader)
    }
}

/// Profile: the playing hand (no line when it is not set), then Matches,
/// Rallies and Last match. The avatar and name are the page's title row,
/// above the pills, so they are not repeated here. Scrolls, so the tiles at a
/// large text size never fall out of reach.
private struct ProfileTab: View {
    let player: Player
    let summary: PlayerSummary

    var body: some View {
        let copy = PlayersCopy.shared
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if let hand = copy.handLine(racketArm: player.racketArm) {
                    Text(hand)
                        .shuttlType(ShuttlType.bodyMedium)
                        .foregroundStyle(Shuttl.textSecondary)
                }
                // One width and one height for the three. Every value is one
                // line at the default text size ("Oct 28", "Oct '25",
                // "None yet") down to 320pt; at a large one a value can wrap,
                // and the captions still stay level: see ShuttlStatTileRow.
                ShuttlStatTileRow(tiles: [
                    .init(value: String(summary.matchCount), label: copy.TILE_MATCHES),
                    .init(value: String(summary.rallyCount), label: copy.TILE_RALLIES),
                    .init(value: PlayerMatchesKt.lastMatchTile(summary: summary), label: copy.TILE_LAST_MATCH),
                ])
            }
            .padding(.horizontal, ShuttlGutter.page)
            .padding(.top, MatchPageLayout.sectionGap)
        }
        // Clear of the Add button, which floats over the page.
        .safeAreaPadding(.bottom, 88)
        .background(Shuttl.bg)
    }
}

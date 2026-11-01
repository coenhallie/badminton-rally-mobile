import SwiftUI
import Shared

/// One player's page: Profile (avatar, name, playing hand, and three tiles)
/// and Matches (the match list filtered to this player, with its filters and
/// sort).
///
/// The title is the player's name as a switcher: a menu of every player,
/// where picking one replaces this page (`onSwitchPlayer`, see
/// `HomePath.toPlayer`). The overflow renames the player, sets the playing
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
/// Mirrors androidApp's PlayerScreen.kt.
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
    /// The page's width, which the switcher's is cut to. See
    /// `PlayerSwitcher.maxWidth`.
    @State private var pageWidth: CGFloat = 0

    private static let profileTab = 0
    private static let matchesTab = 1
    /// The switcher's avatar: the mock's small disc beside the name.
    private static let switcherAvatarSize: CGFloat = 26

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
            .onGeometryChange(for: CGFloat.self, of: { $0.size.width }) { pageWidth = $0 }
            .background(Shuttl.bg)
            .navigationBarTitleDisplayMode(.inline)
            .shuttlNavigationBarBackground()
            .toolbar { toolbarContent(shown: shown) }
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
    private func toolbarContent(shown: Player?) -> some ToolbarContent {
        // Beside Back, as the mock and androidApp's bar have it, rather than
        // centred: the name gets the bar's whole middle, not what is left
        // between two equal margins.
        ToolbarItem(placement: .topBarLeading) {
            if let shown, pageWidth > 0 {
                PlayerSwitcher(
                    current: shown,
                    players: model.players,
                    avatarSize: Self.switcherAvatarSize,
                    barWidth: pageWidth,
                    onPick: { picked in
                        if picked != shown.id { onSwitchPlayer(picked) }
                    }
                )
            }
        }
        .sharedBackgroundHiddenWhereSupported()
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

/// The title: the player's avatar and name on a pill with a chevron, which
/// opens a menu of every player (sorted by name, as the repository publishes
/// them), the current one ticked. A 40-character name is cut, never the
/// chevron. The pill draws 38pt tall, in a 44pt target.
private struct PlayerSwitcher: View {
    let current: Player
    let players: [Player]
    let avatarSize: CGFloat
    /// The bar's width (the page's), which `maxWidth` is worked out from.
    let barWidth: CGFloat
    let onPick: (String) -> Void

    /// Read here, in this small view, rather than by the page: it changes on
    /// every progress tick of a run. See `BackgroundWorkScope`.
    @Environment(\.backgroundWork) private var work

    /// What the bar's other controls take beside the switcher, measured on
    /// iOS 26's bar with a 40-character name: Back and the space after it
    /// (about 88pt from the edge to the switcher), the trailing group with its
    /// margin, and the gap the bar keeps between the two groups. Short of
    /// that gap, the bar folds the trailing items into a "more" button (a
    /// 2pt-wider switcher already did). The group is the overflow alone
    /// (`idleControlsWidth`), or the background-work indicator and the
    /// overflow while a run is going (`busyControlsWidth`), and the name gives
    /// way to the indicator as androidApp's title does to its actions.
    private static let idleControlsWidth: CGFloat = 178
    private static let busyControlsWidth: CGFloat = 236

    /// The most the switcher may take. A bar measures its items at their
    /// ideal width and does not cut them: an uncut 40-character name ran over
    /// Back and the overflow, or had the bar drop the switcher altogether.
    private var maxWidth: CGFloat {
        let controls = work == nil ? Self.idleControlsWidth : Self.busyControlsWidth
        return max(barWidth - controls, avatarSize * 3)
    }

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
            CappedWidth(max: maxWidth) { label }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(current.name)
        .accessibilityHint(PlayersCopy.shared.SWITCH_PLAYER)
    }

    private var label: some View {
        HStack(spacing: 0) {
            PlayerAvatar(name: current.name, size: avatarSize)
            Text(current.name)
                .shuttlType(ShuttlType.titleMedium)
                .foregroundStyle(Shuttl.text)
                .lineLimit(1)
                .truncationMode(.tail)
                .padding(.leading, 8)
            Image(systemName: "chevron.down")
                .shuttlType(ShuttlType.labelMedium)
                .foregroundStyle(Shuttl.textSecondary)
                .padding(.leading, 6)
                .layoutPriority(1)
        }
        // The mock's 6 round the avatar, 12 after the chevron.
        .padding(.leading, 6)
        .padding(.trailing, 12)
        .padding(.vertical, 6)
        .background(Shuttl.bgSecondary, in: Capsule())
        .frame(minHeight: ShuttlTarget.minimum)
        .contentShape(Rectangle())
    }
}

/// Its one subview at the width it is offered, but never wider than `max`,
/// including when it is offered no width at all, which is how a bar measures
/// its items. The name inside is then cut rather than drawn whole over Back.
private struct CappedWidth: Layout {
    let max: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let only = subviews.first else { return .zero }
        return only.sizeThatFits(ProposedViewSize(width: min(proposal.width ?? max, max), height: proposal.height))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(at: bounds.origin, proposal: ProposedViewSize(bounds.size))
    }
}

/// Profile: the avatar beside the name and the playing hand (no line when it
/// is not set), then Matches, Rallies and Last match. Scrolls, so a long name
/// at a large text size never pushes the tiles out of reach.
private struct ProfileTab: View {
    let player: Player
    let summary: PlayerSummary

    var body: some View {
        let copy = PlayersCopy.shared
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 14) {
                    PlayerAvatar(name: player.name, size: PlayerAvatar.profileSize)
                    VStack(alignment: .leading, spacing: 2) {
                        // Two lines at most: a 40-character name beside the
                        // avatar would otherwise run to three.
                        Text(player.name)
                            .shuttlType(ShuttlType.headlineMedium)
                            .foregroundStyle(Shuttl.textHeading)
                            .lineLimit(2)
                            .truncationMode(.tail)
                        if let hand = copy.handLine(racketArm: player.racketArm) {
                            Text(hand)
                                .shuttlType(ShuttlType.bodyMedium)
                                .foregroundStyle(Shuttl.textSecondary)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .accessibilityElement(children: .combine)
                // One height for the three. Every value is one line at the
                // default text size ("Oct 28", "Oct '25", "None yet"); at a
                // large one a value can wrap, and the captions still stay
                // level: see ShuttlStatTile's `compact`.
                HStack(spacing: 8) {
                    tile(String(summary.matchCount), copy.TILE_MATCHES)
                    tile(String(summary.rallyCount), copy.TILE_RALLIES)
                    tile(PlayerMatchesKt.lastMatchTile(summary: summary), copy.TILE_LAST_MATCH)
                }
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 20)
            }
            .padding(.horizontal, ShuttlGutter.page)
            .padding(.top, MatchPageLayout.sectionGap)
        }
        // Clear of the Add button, which floats over the page.
        .safeAreaPadding(.bottom, 88)
        .background(Shuttl.bg)
    }

    private func tile(_ value: String, _ label: String) -> some View {
        ShuttlStatTile(value: value, unit: "", label: label, compact: true)
            .accessibilityElement(children: .combine)
    }
}

private extension ToolbarContent {
    /// The switcher is its own pill: iOS 26's shared glass behind a bar item
    /// would draw a second capsule round it.
    @ToolbarContentBuilder
    func sharedBackgroundHiddenWhereSupported() -> some ToolbarContent {
        if #available(iOS 26.0, *) {
            sharedBackgroundVisibility(.hidden)
        } else {
            self
        }
    }
}

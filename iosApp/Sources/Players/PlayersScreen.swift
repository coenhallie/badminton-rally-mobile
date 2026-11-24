import SwiftUI
import Shared

/// The root screen: one row per player, an "Add player" row, the way to All
/// matches, and the Add button. Every other screen is pushed over it. Its
/// title carries the background-work indicator and the Settings gear, and it
/// draws the app's root toast, which HomeView posts to: finished runs, record
/// and import failures, a failed match refresh, a failed player save.
///
/// Each row's detail is read off the match list (`listModel`, the app's one
/// instance), the same list a player's Matches tab filters, so the count on
/// the row and the matches behind it cannot disagree.
///
/// Mirrors androidApp's PlayersScreen.kt.
struct PlayersScreen: View {
    let model: PlayersModel
    let listModel: ClipListModel
    var accessModel: PlayerAccessModel? = nil
    let onOpenPlayer: (String) -> Void
    var onOpenLinkedProfile: (String) -> Void = { _ in }
    let onAllMatches: () -> Void
    let onAdd: () -> Void
    let onSettings: () -> Void
    /// Whether the add-player sheet is open. HomeView folds it into "nothing
    /// above the list", which gates announcing a finished run.
    let onOverlayChange: (Bool) -> Void
    /// What the root says. Owned by HomeView.
    @Binding var toast: ToastMessage?

    @State private var addingPlayer = false

    var body: some View {
        VStack(spacing: 0) {
            HomeHeader(PlayersCopy.shared.SCREEN_TITLE) {
                BackgroundWorkAction()
                Button(action: onSettings) {
                    Image(systemName: "gearshape")
                        .shuttlType(ShuttlType.titleMedium)
                        .foregroundStyle(Shuttl.textSecondary)
                        .frame(width: ShuttlTarget.minimum, height: ShuttlTarget.minimum)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(MatchPageCopy.shared.SETTINGS)
            }
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(Shuttl.bg)
        .overlay(alignment: .bottomTrailing) {
            AddMatchButton(action: onAdd)
                .padding(.trailing, 16)
                .padding(.bottom, 16)
        }
        // Above the Add button rather than over it: the 56pt button, its 16pt
        // margin, and 16pt of air.
        // HomeView ends it when the coach leaves the list screens; see
        // `RootToasts.listsLeft`.
        .shuttlToast($toast, bottomInset: 88, endsWhenScreenGoes: false)
        // Both while this screen is in front, and stopped under a push: the
        // rows' details read the match list, and nothing else runs its loops
        // while Players is the screen on top. See `ClipListModel.start()`.
        .task { await listModel.start() }
        .task { await model.start() }
        .sheet(isPresented: $addingPlayer) {
            PlayerNameSheet(
                title: PlayersCopy.shared.ADD_DIALOG_TITLE,
                initialName: "",
                initialArm: nil,
                showArm: true
            ) { name, arm in
                // The new row appears on its own, through the repository's
                // list; a failure stays in the sheet with the name typed.
                switch await model.create(name: name, arm: arm) {
                case .created: return nil
                case .failed(let message): return message
                }
            }
        }
        .onChange(of: addingPlayer, initial: true) { _, open in onOverlayChange(open) }
        // Leaving the screen takes its sheet with it, whatever was last said.
        .onDisappear { onOverlayChange(false) }
    }

    @ViewBuilder private var content: some View {
        if model.players.isEmpty {
            // Until the first refresh settles, show a centered spinner rather
            // than blank space; once settled with no players, show the empty or
            // retry state.
            if model.showsNoPlayers {
                noPlayers
            } else {
                ProgressView()
                    .tint(Shuttl.accent)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        } else {
            list
        }
    }

    /// Single O(M) pass over `matchList.mine` building every player's summary,
    /// avoiding an O(P × M) Kotlin-bridged scan inside `ForEach`.
    static func playerSummaries(from matchList: MatchList) -> [String: PlayerSummary] {
        struct Accumulator {
            var matchCount: Int32 = 0
            var rallyCount: Int32 = 0
            var lastMatchAtEpochMs: Int64? = nil
        }
        var acc: [String: Accumulator] = [:]
        for item in matchList.mine {
            let homeId = item.homePlayerId
            let awayId = item.awayPlayerId
            if let homeId {
                var entry = acc[homeId] ?? Accumulator()
                entry.matchCount += 1
                entry.rallyCount += item.rallyCount
                entry.lastMatchAtEpochMs = entry.lastMatchAtEpochMs.map { max($0, item.sortAtEpochMs) }
                    ?? item.sortAtEpochMs
                acc[homeId] = entry
            }
            if let awayId, awayId != homeId {
                var entry = acc[awayId] ?? Accumulator()
                entry.matchCount += 1
                entry.rallyCount += item.rallyCount
                entry.lastMatchAtEpochMs = entry.lastMatchAtEpochMs.map { max($0, item.sortAtEpochMs) }
                    ?? item.sortAtEpochMs
                acc[awayId] = entry
            }
        }
        return acc.mapValues {
            PlayerSummary(
                matchCount: $0.matchCount,
                rallyCount: $0.rallyCount,
                lastMatchAtEpochMs: $0.lastMatchAtEpochMs.map { KotlinLong(value: $0) }
            )
        }
    }

    private func refresh() async {
        async let playersRefresh: Void = model.refresh()
        async let matchesRefresh: Void = listModel.refresh()
        async let accessRefresh: Void = accessModel?.refreshAccessStates() ?? ()
        async let linkedRefresh: Void = accessModel?.refreshLinkedProfiles() ?? ()
        _ = await (playersRefresh, matchesRefresh, accessRefresh, linkedRefresh)
    }

    private var list: some View {
        // Precomputed once in O(M) before ForEach rather than scanning the
        // match list per player row.
        let summaries = Self.playerSummaries(from: listModel.matchList)
        let emptySummary = PlayerSummary(matchCount: 0, rallyCount: 0, lastMatchAtEpochMs: nil)
        let linkedProfiles = accessModel?.linkedProfiles ?? []
        return ScrollView {
            LazyVStack(spacing: DrawerList.rowGap) {
                if let primaryLinked = linkedProfiles.first {
                    myProfileCard(profile: primaryLinked, totalCount: linkedProfiles.count)
                        .padding(.bottom, 6)
                }
                ForEach(model.players, id: \.id) { player in
                    PlayerRow(
                        player: player,
                        detail: PlayerMatchesKt.playerRowDetail(
                            summary: summaries[player.id] ?? emptySummary
                        ),
                        accessStatus: accessModel?.accessByPlayerId[player.id]?.status,
                        onTap: { onOpenPlayer(player.id) }
                    )
                }
                AddPlayerRow { addingPlayer = true }
                // The mock's 12 above the button, of which the row gap is
                // already half.
                allMatchesButton
                    .padding(.top, 12 - DrawerList.rowGap)
            }
            .padding(.horizontal, DrawerList.sideMargin)
            .padding(.top, DrawerList.listTopInset)
        }
        .refreshable { await refresh() }
        // The last row and All matches scroll clear of the Add button, which
        // floats over the list: see `MatchesList`'s own foot for the 88.
        .safeAreaPadding(.bottom, 88)
    }

    private func myProfileCard(profile: LinkedPlayerProfile, totalCount: Int) -> some View {
        let shape = RoundedRectangle(cornerRadius: ShuttlRadius.medium)
        let subtitle = totalCount > 1
            ? "\(PlayerInviteCopy.shared.coachedBy(coachEmail: profile.coachEmail)) · +\(totalCount - 1)"
            : PlayerInviteCopy.shared.coachedBy(coachEmail: profile.coachEmail)
        return VStack(alignment: .leading, spacing: 6) {
            Text(PlayerInviteCopy.shared.MY_PROFILE_SECTION.uppercased())
                .shuttlType(ShuttlType.labelSmall)
                .foregroundStyle(Shuttl.textSecondary)
            Button {
                onOpenLinkedProfile(profile.playerId)
            } label: {
                HStack(spacing: DrawerList.gap) {
                    PlayerAvatar(name: profile.name, size: PlayerAvatar.rowSize)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(profile.name)
                            .shuttlType(ShuttlType.titleMedium)
                            .foregroundStyle(Shuttl.text)
                            .lineLimit(1)
                        Text(subtitle)
                            .shuttlType(ShuttlType.bodySmall)
                            .foregroundStyle(Shuttl.textSecondary)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: "chevron.right")
                        .shuttlType(ShuttlType.labelMedium)
                        .foregroundStyle(Shuttl.textSecondary)
                        .accessibilityHidden(true)
                }
                .padding(.horizontal, DrawerList.rowPaddingH)
                .padding(.vertical, DrawerList.rowPaddingV)
                .background(Shuttl.bgSecondary, in: shape)
                .overlay(shape.strokeBorder(Shuttl.border, lineWidth: 1))
                .contentShape(shape)
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
        }
    }

    /// No players to list: the first-run invitation, or, when the load failed
    /// with nothing cached, a retry, so a failed load never reads as "no
    /// players yet" and invites a duplicate. All matches stays below either
    /// way, so a coach with history is never stranded.
    ///
    /// Scrollable even though it rarely scrolls: at a large text size in
    /// landscape the button would otherwise fall off the bottom. Top-aligned at
    /// a fixed offset, as the match list's empty state is.
    private var noPlayers: some View {
        let copy = PlayersCopy.shared
        let linkedProfiles = accessModel?.linkedProfiles ?? []
        return ScrollView {
            VStack(spacing: 8) {
                if let primaryLinked = linkedProfiles.first {
                    myProfileCard(profile: primaryLinked, totalCount: linkedProfiles.count)
                        .padding(.horizontal, DrawerList.sideMargin)
                        .padding(.bottom, 16)
                }
                if model.loadFailed {
                    ShuttlEmptyState(
                        systemImage: "figure.stand",
                        title: copy.LOAD_FAILED,
                        message: copy.LOAD_FAILED_BODY
                    ) {
                        Button(copy.RETRY) { Task { await model.refresh() } }
                            .buttonStyle(CompactPillButtonStyle(secondary: true, fullTarget: true))
                    }
                } else {
                    ShuttlEmptyState(
                        systemImage: "figure.stand",
                        title: copy.EMPTY_TITLE,
                        message: copy.EMPTY_BODY
                    ) {
                        Button { addingPlayer = true } label: {
                            Label(copy.ADD_PLAYER, systemImage: "plus")
                        }
                        .buttonStyle(CompactPillButtonStyle(fullTarget: true))
                    }
                }
                allMatchesButton
                    .padding(.horizontal, DrawerList.sideMargin)
            }
            .padding(.top, linkedProfiles.isEmpty ? 48 : DrawerList.listTopInset)
        }
        .refreshable { await refresh() }
        .safeAreaPadding(.bottom, 88)
    }

    private var allMatchesButton: some View {
        Button(PlayersCopy.shared.ALL_MATCHES, action: onAllMatches)
            .buttonStyle(SecondaryPillButtonStyle())
    }
}

/// One player: avatar, name, "12 matches · last Oct 28", and a chevron to
/// their page.
private struct PlayerRow: View {
    let player: Player
    let detail: String
    var accessStatus: PlayerAccessStatus? = nil
    let onTap: () -> Void

    private var statusDotColor: Color? {
        switch accessStatus {
        case .linked: return Shuttl.accent
        case .pending: return Shuttl.warning
        default: return nil
        }
    }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: ShuttlRadius.medium)
        Button(action: onTap) {
            HStack(spacing: DrawerList.gap) {
                PlayerAvatar(name: player.name, size: PlayerAvatar.rowSize)
                VStack(alignment: .leading, spacing: 3) {
                    Text(player.name)
                        .shuttlType(ShuttlType.titleMedium)
                        .foregroundStyle(Shuttl.text)
                        .lineLimit(1)
                    Text(detail)
                        .shuttlType(ShuttlType.bodySmall)
                        .foregroundStyle(Shuttl.textSecondary)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if let dotColor = statusDotColor {
                    Circle()
                        .fill(dotColor)
                        .frame(width: 8, height: 8)
                        .accessibilityLabel(
                            accessStatus == .linked
                                ? PlayerInviteCopy.shared.ACCESS_BADGE_CONNECTED
                                : PlayerInviteCopy.shared.ACCESS_BADGE_PENDING
                        )
                }
                Image(systemName: "chevron.right")
                    .shuttlType(ShuttlType.labelMedium)
                    .foregroundStyle(Shuttl.textSecondary)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, DrawerList.rowPaddingH)
            .padding(.vertical, DrawerList.rowPaddingV)
            .background(Shuttl.bgTertiary, in: shape)
            // The whole card opens the page, not only its text.
            .contentShape(shape)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }
}

/// The list's last row: the mock's dashed outline on no fill, so it reads as a
/// slot for a player rather than as one, with a "+" where the avatar goes.
private struct AddPlayerRow: View {
    let onTap: () -> Void

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: ShuttlRadius.medium)
        Button(action: onTap) {
            HStack(spacing: DrawerList.gap) {
                Image(systemName: "plus")
                    .shuttlType(ShuttlType.titleMedium)
                    .foregroundStyle(Shuttl.textSecondary)
                    .frame(width: PlayerAvatar.rowSize, height: PlayerAvatar.rowSize)
                    .background(Shuttl.bgTertiary, in: Circle())
                    .accessibilityHidden(true)
                Text(PlayersCopy.shared.ADD_PLAYER)
                    .shuttlType(ShuttlType.titleMedium)
                    .foregroundStyle(Shuttl.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, DrawerList.rowPaddingH)
            .padding(.vertical, DrawerList.rowPaddingV)
            // Drawn inside the shape (`strokeBorder`), so the card is the
            // same size as a player's and no half of the line is lost.
            .overlay(shape.strokeBorder(Shuttl.borderSecondary, style: StrokeStyle(lineWidth: 1, dash: [4, 3])))
            .contentShape(shape)
        }
        .buttonStyle(.plain)
    }
}

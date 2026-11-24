import SwiftUI
import Shared

/// Routes pushed over `PlayerHomeView` for `AccountRole.player`.
enum PlayerHomeRoute: Hashable {
    case match(MatchRoute)
    case settings
}

/// Focused Player home for `AccountRole.player`. Skips the coach Players list,
/// All matches, and `+` FAB, landing straight on the player's own
/// `Profile | Matches | Drills` view with multi-coach switcher and unlinked
/// recovery state. Mirrors androidApp's `PlayerHomeScreen.kt`.
struct PlayerHomeView: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    let localAnalysis: LocalAnalysisRunner?

    @State private var path: [PlayerHomeRoute] = []
    @State private var accessModel: PlayerAccessModel
    @State private var listModel: ClipListModel
    @State private var playersModel: PlayersModel
    @State private var intake: LocalVideoIntake
    @State private var selectedPlayerId: String? = nil
    @State private var redeemSheetOpen = false
    @State private var toast: ToastMessage? = nil

    init(rally: RallyApp, analyze: AnalyzeCoordinator, localAnalysis: LocalAnalysisRunner?) {
        self.rally = rally
        self.analyze = analyze
        self.localAnalysis = localAnalysis
        _accessModel = State(initialValue: PlayerAccessModel(rally: rally))
        _listModel = State(initialValue: ClipListModel(
            rally: rally, analyze: analyze,
            liveDeviceRuns: { [localAnalysis] in localAnalysis?.deviceRuns ?? [:] }
        ))
        _playersModel = State(initialValue: PlayersModel(rally: rally))
        _intake = State(initialValue: LocalVideoIntake(rally: rally))
    }

    private var activeProfile: LinkedPlayerProfile? {
        if let selectedPlayerId, let found = accessModel.linkedProfiles.first(where: { $0.playerId == selectedPlayerId }) {
            return found
        }
        return accessModel.linkedProfiles.first
    }

    var body: some View {
        NavigationStack(path: $path) {
            VStack(spacing: 0) {
                HomeHeader(SignInCopy.shared.WORDMARK, titleRole: ShuttlType.wordmark) {
                    Button { path.append(.settings) } label: {
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
            .toolbar(.hidden, for: .navigationBar)
            .shuttlToast($toast, bottomInset: 24, endsWhenScreenGoes: false)
            .navigationDestination(for: PlayerHomeRoute.self) { route in
                switch route {
                case .match(let matchRoute):
                    MatchView(
                        rally: rally, analyze: analyze, localAnalysis: localAnalysis,
                        listModel: listModel, playersModel: playersModel, route: matchRoute
                    )
                case .settings:
                    SettingsView(
                        rally: rally,
                        accountRole: .player,
                        accessModel: accessModel,
                        onLinkedCoach: { redeemed in
                            selectedPlayerId = redeemed.playerId
                            Task { await listModel.refresh() }
                            path.removeAll()
                            toast = ToastMessage(
                                text: PlayerInviteCopy.shared.linkedSuccessToast(playerName: redeemed.playerName),
                                actionLabel: nil, action: nil
                            )
                        }
                    )
                }
            }
        }
        .feedsMatchList(model: listModel, localAnalysis: localAnalysis)
        .task { await accessModel.observe() }
        .task { await listModel.start() }
        .task { await accessModel.refreshLinkedProfiles() }
        .onChange(of: activeProfile?.playerId, initial: true) { _, id in
            if let id, selectedPlayerId != id { selectedPlayerId = id }
        }
        .sheet(isPresented: $redeemSheetOpen) {
            RedeemInviteSheet(
                onRedeem: { await accessModel.redeemExistingCode($0) },
                onLinked: { redeemed in
                    selectedPlayerId = redeemed.playerId
                    Task { await listModel.refresh() }
                    toast = ToastMessage(
                        text: PlayerInviteCopy.shared.linkedSuccessToast(playerName: redeemed.playerName),
                        actionLabel: nil, action: nil
                    )
                }
            )
        }
    }

    @ViewBuilder
    private var content: some View {
        if !accessModel.linkedProfilesLoaded && accessModel.linkedProfiles.isEmpty {
            ProgressView()
                .tint(Shuttl.accent)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let profile = activeProfile {
            LinkedProfileBody(
                rally: rally,
                analyze: analyze,
                intake: intake,
                profile: profile,
                allProfiles: accessModel.linkedProfiles,
                onSelectProfile: { selectedPlayerId = $0.playerId },
                listModel: listModel,
                playersModel: playersModel,
                onMatchTap: { path.append(.match($0)) },
                onRefreshAll: {
                    async let p: Void = accessModel.refreshLinkedProfiles()
                    async let m: Void = listModel.refresh()
                    _ = await (p, m)
                }
            )
        } else {
            ScrollView {
                ShuttlEmptyState(
                    systemImage: "figure.stand",
                    title: PlayerInviteCopy.shared.UNLINKED_TITLE,
                    message: PlayerInviteCopy.shared.UNLINKED_BODY
                ) {
                    Button(PlayerInviteCopy.shared.UNLINKED_CTA) { redeemSheetOpen = true }
                        .buttonStyle(CompactPillButtonStyle(fullTarget: true))
                }
                .padding(.top, 48)
            }
            .refreshable {
                async let p: Void = accessModel.refreshLinkedProfiles()
                async let m: Void = listModel.refresh()
                _ = await (p, m)
            }
        }
    }
}

/// Dual-role coach destination: opens a coach's own linked player profile from
/// another coach in read-only mode, pushed over Coach Home.
struct LinkedProfileView: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    let intake: LocalVideoIntake
    let initialPlayerId: String
    let accessModel: PlayerAccessModel
    let listModel: ClipListModel
    let playersModel: PlayersModel
    let onMatchTap: (MatchRoute) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var selectedPlayerId: String

    init(
        rally: RallyApp,
        analyze: AnalyzeCoordinator,
        intake: LocalVideoIntake,
        initialPlayerId: String,
        accessModel: PlayerAccessModel,
        listModel: ClipListModel,
        playersModel: PlayersModel,
        onMatchTap: @escaping (MatchRoute) -> Void
    ) {
        self.rally = rally
        self.analyze = analyze
        self.intake = intake
        self.initialPlayerId = initialPlayerId
        self.accessModel = accessModel
        self.listModel = listModel
        self.playersModel = playersModel
        self.onMatchTap = onMatchTap
        _selectedPlayerId = State(initialValue: initialPlayerId)
    }

    private var activeProfile: LinkedPlayerProfile? {
        accessModel.linkedProfiles.first(where: { $0.playerId == selectedPlayerId })
            ?? accessModel.linkedProfiles.first
    }

    var body: some View {
        Group {
            if let profile = activeProfile {
                LinkedProfileBody(
                    rally: rally,
                    analyze: analyze,
                    intake: intake,
                    profile: profile,
                    allProfiles: accessModel.linkedProfiles,
                    onSelectProfile: { selectedPlayerId = $0.playerId },
                    listModel: listModel,
                    playersModel: playersModel,
                    onMatchTap: onMatchTap,
                    onRefreshAll: {
                        async let p: Void = accessModel.refreshLinkedProfiles()
                        async let m: Void = listModel.refresh()
                        _ = await (p, m)
                    }
                )
            } else {
                Color.clear
            }
        }
        .background(Shuttl.bg)
        .navigationTitle(PlayerInviteCopy.shared.MY_PROFILE_SECTION)
        .navigationBarTitleDisplayMode(.inline)
        .shuttlNavigationBarBackground()
        .onChange(of: activeProfile == nil) { _, gone in
            if gone && accessModel.linkedProfilesLoaded { dismiss() }
        }
    }
}

/// Shared read-only `Profile | Matches | Drills` content for linked players
/// (used by both `PlayerHomeView` and dual-role `LinkedProfileView`).
private struct LinkedProfileBody: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    let intake: LocalVideoIntake
    let profile: LinkedPlayerProfile
    let allProfiles: [LinkedPlayerProfile]
    let onSelectProfile: (LinkedPlayerProfile) -> Void
    let listModel: ClipListModel
    let playersModel: PlayersModel
    let onMatchTap: (MatchRoute) -> Void
    let onRefreshAll: () async -> Void

    @State private var tab = 0
    @State private var matchesFilter: MatchListFilter = .all
    @State private var matchesSort: MatchListSort = .newest

    var body: some View {
        let summary = PlayerMatchesKt.playerSummary(list: listModel.matchList, playerId: profile.playerId)
        VStack(spacing: 0) {
            profileHeader
                .padding(.horizontal, ShuttlGutter.page)
                .padding(.vertical, 8)

            ShuttlPillTabs(
                labels: [PlayersCopy.shared.TAB_PROFILE, PlayersCopy.shared.TAB_MATCHES, PlayersCopy.shared.TAB_DRILLS],
                selectedIndex: tab,
                onSelect: { tab = $0 },
                accessibilityLabel: PlayersCopy.shared.PAGE_SECTIONS,
                setsCeiling: true
            )
            .padding(.horizontal, ShuttlGutter.page)
            .padding(.top, 4)

            ZStack {
                profileTab(summary: summary)
                    .opacity(tab == 0 ? 1 : 0)
                    .zIndex(tab == 0 ? 1 : 0)
                    .allowsHitTesting(tab == 0)
                    .accessibilityHidden(tab != 0)

                MatchesList(
                    rally: rally,
                    analyze: analyze,
                    intake: intake,
                    model: listModel,
                    playersModel: playersModel,
                    onMatchTap: onMatchTap,
                    onCourtMarking: { _ in },
                    heroPaused: true,
                    onOverlayChange: { _ in },
                    playerId: profile.playerId,
                    bottomInset: 24,
                    filter: $matchesFilter,
                    sort: $matchesSort,
                    emptyMessage: "Matches from your coach will appear here."
                )
                .id(profile.playerId)
                .opacity(tab == 1 ? 1 : 0)
                .zIndex(tab == 1 ? 1 : 0)
                .allowsHitTesting(tab == 1)
                .accessibilityHidden(tab != 1)

                if tab == 2 {
                    PlayerDrillsTab(
                        playerId: profile.playerId,
                        racketArm: profile.racketArm,
                        drillProgress: rally.drillProgress,
                        currentUserId: rally.auth.currentUserId(),
                        otherPartyLabel: "Coach",
                        isCoach: false,
                        onSyncDrills: { listModel.syncDrills() }
                    )
                    .id(profile.playerId)
                    .zIndex(2)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .pillLabelCeilingHost()
    }

    @ViewBuilder
    private var profileHeader: some View {
        let headerRow = HStack(spacing: 12) {
            PlayerAvatar(name: profile.name, size: PlayerAvatar.rowSize)
            VStack(alignment: .leading, spacing: 2) {
                Text(profile.name)
                    .shuttlType(ShuttlType.titleMedium)
                    .foregroundStyle(Shuttl.textHeading)
                    .lineLimit(1)
                Text(PlayerInviteCopy.shared.coachedBy(coachEmail: profile.coachEmail))
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textSecondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            if allProfiles.count > 1 {
                Image(systemName: "chevron.down")
                    .shuttlType(ShuttlType.labelMedium)
                    .foregroundStyle(Shuttl.textSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())

        if allProfiles.count > 1 {
            Menu {
                ForEach(allProfiles, id: \.playerId) { option in
                    Button {
                        onSelectProfile(option)
                    } label: {
                        HStack {
                            Text("\(option.name) · \(option.coachEmail)")
                            if option.playerId == profile.playerId {
                                Image(systemName: "checkmark")
                            }
                        }
                    }
                }
            } label: {
                headerRow
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .background(
                        Shuttl.bgSecondary,
                        in: RoundedRectangle(cornerRadius: ShuttlRadius.medium, style: .continuous)
                    )
            }
            .buttonStyle(.plain)
            .accessibilityLabel(PlayerInviteCopy.shared.SWITCH_COACH_PROFILE)
        } else {
            headerRow
                .padding(.vertical, 4)
        }
    }

    private func profileTab(summary: PlayerSummary) -> some View {
        let copy = PlayersCopy.shared
        return ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let hand = copy.handLine(racketArm: profile.racketArm) {
                    Text(hand)
                        .shuttlType(ShuttlType.bodyMedium)
                        .foregroundStyle(Shuttl.textSecondary)
                }

                ShuttlStatTileRow(tiles: [
                    .init(value: String(summary.matchCount), label: copy.TILE_MATCHES),
                    .init(value: String(summary.rallyCount), label: copy.TILE_RALLIES),
                    .init(value: PlayerMatchesKt.lastMatchTile(summary: summary), label: copy.TILE_LAST_MATCH),
                ])

                VStack(alignment: .leading, spacing: 4) {
                    Text(PlayerInviteCopy.shared.coachedBy(coachEmail: profile.coachEmail))
                        .shuttlType(ShuttlType.bodyMedium)
                        .foregroundStyle(Shuttl.textHeading)
                    Text(PlayerInviteCopy.shared.linkedDateLabel(
                        linkedAt: profile.linkedAt, timeZone: .companion.currentSystemDefault()
                    ))
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.textSecondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .background(
                    Shuttl.bgSecondary,
                    in: RoundedRectangle(cornerRadius: ShuttlRadius.large, style: .continuous)
                )
            }
            .padding(.horizontal, ShuttlGutter.page)
            .padding(.top, MatchPageLayout.sectionGap)
            .padding(.bottom, 24)
        }
        .refreshable { await onRefreshAll() }
        .background(Shuttl.bg)
    }
}

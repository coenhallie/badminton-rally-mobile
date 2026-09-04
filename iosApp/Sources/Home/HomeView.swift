import SwiftUI
import Shared

/// The create-and-finish flow, end to end: first the board pushed straight
/// from creating a new match (no match page underneath it yet), then - once
/// it finishes - the match page it lands on. One binding carries both stages
/// rather than two separate ones (an earlier version of this fix used two,
/// `NewMatchScoringRoute` and `FinishedMatchRoute`): SwiftUI reliably replaces
/// what an `item:`-bound destination shows when that SAME binding's value
/// changes to a new one, but does not reliably settle a pop on one binding
/// racing a push on a DIFFERENT binding shortly after - confirmed on-device,
/// the destination came up with its content area permanently blank for the
/// full length of a 30-second wait. Folding both stages into one binding turns
/// "pop this, then push that" into a single reassignment, which is the
/// transition SwiftUI does handle correctly (the same way `.sheet(item:)`
/// swaps to a new item without needing to be dismissed and re-presented).
/// See task-13-report.md's create-and-finish investigation for the evidence.
private enum CreateFlowDestination: Hashable, Identifiable {
    case scoring(scoreLogId: String)
    case finished(scoreLogId: String, attach: AttachIntent?)

    var id: String {
        switch self {
        case .scoring(let scoreLogId): return "scoring-\(scoreLogId)"
        case .finished(let scoreLogId, _): return "finished-\(scoreLogId)"
        }
    }
}

/// The app's front door: a rotating hero line, two pill buttons, and the
/// drawer that used to be the whole screen. Owns the `NavigationStack` and
/// every destination `MatchesList` (formerly `ClipListView`) used to
/// register itself - `MatchesList` reports taps upward through closures
/// instead, because it no longer has a stack of its own to push onto once it
/// lives behind the drawer.
struct HomeView: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator

    @State private var drawerOpen = false
    @State private var showAddSheet = false
    @State private var showLabels = false
    @State private var showNewMatch = false
    @State private var showRecorder = false
    @State private var showImporter = false
    @State private var themeMode: ThemeMode = .light
    @State private var intake: LocalVideoIntake
    /// The auto-open case only: straight after a record/import that isn't for
    /// an existing match, so the coach can name it. The row menu's own "Edit
    /// details" keeps a separate, `MatchesList`-owned target for the same
    /// sheet - the two never need to agree on one value at the same time.
    @State private var detailsTarget: MatchDetailsTarget? = nil

    // Every destination MatchesList used to carry, now owned here. Three of
    // them (courtMarking, matchTap, localPlayer) are still triggered from
    // inside the list, only now through a closure instead of an owned
    // binding or a `NavigationLink(value:)` reaching up through the
    // environment. The rest (labels, new match, the create-and-finish flow)
    // had no trigger left once this redesign deleted the list's toolbar, so
    // they move here wholesale, state and all.
    @State private var courtMarkingRoute: CourtMarkingRoute? = nil
    /// Deliberately separate from `createFlowTarget`: a plain row tap only
    /// ever needs `MatchView`, never the board, so folding this into the
    /// create-and-finish binding would give it a case it never uses. Keep the
    /// two apart - `CreateFlowDestination`'s doc comment is about racing pops
    /// and pushes on DIFFERENT bindings, not about how many bindings exist in
    /// total, and this route and that one are never on screen at once.
    @State private var matchRoute: MatchRoute? = nil
    @State private var localPlayerRoute: LocalPlayerRoute? = nil
    @State private var createFlowTarget: CreateFlowDestination? = nil

    init(rally: RallyApp, analyze: AnalyzeCoordinator) {
        self.rally = rally
        self.analyze = analyze
        _intake = State(initialValue: LocalVideoIntake(rally: rally))
    }

    var body: some View {
        NavigationStack {
            ZStack(alignment: .leading) {
                homeScreen
                MatchesDrawer(isOpen: $drawerOpen, onLabels: { showLabels = true }, onSignOut: signOut) {
                    MatchesList(
                        rally: rally,
                        analyze: analyze,
                        intake: intake,
                        onMatchTap: { matchRoute = $0 },
                        onCourtMarking: { courtMarkingRoute = $0 },
                        onLocalPlayer: { localPlayerRoute = $0 }
                    )
                }
            }
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(item: $courtMarkingRoute) { route in
                CourtMarkingView(rally: rally, analyze: analyze, entryId: route.entryId)
            }
            .navigationDestination(isPresented: $showLabels) {
                LabelsView(rally: rally)
            }
            .navigationDestination(item: $matchRoute) { route in
                MatchView(rally: rally, analyze: analyze, route: route)
            }
            .navigationDestination(isPresented: $showNewMatch) {
                NewMatchView(rally: rally) { id in
                    // Straight to the board, not back to Home and not to the
                    // record: creating a match courtside means being about to
                    // score it.
                    showNewMatch = false
                    createFlowTarget = .scoring(scoreLogId: id)
                }
            }
            .navigationDestination(item: $createFlowTarget) { target in
                // Both stages of the create-and-finish flow share this one
                // `item:` registration - see `CreateFlowDestination`'s own doc
                // comment for why splitting it back into two was the bug.
                switch target {
                case .scoring(let scoreLogId):
                    ScoringView(
                        rally: rally,
                        scoreLogId: scoreLogId,
                        matchPageAlreadyOpen: false,
                        onFinished: onScoringFinished(scoreLogId)
                    )
                case .finished(let scoreLogId, let attach):
                    MatchView(
                        rally: rally, analyze: analyze,
                        route: MatchRoute(scoreLogId: scoreLogId, videoId: nil, attach: attach)
                    )
                }
            }
            .navigationDestination(item: $localPlayerRoute) { route in
                LocalPlayerView(rally: rally, analyze: analyze, entryId: route.entryId)
            }
        }
        .sheet(isPresented: $showAddSheet) {
            AddMatchSheet(
                onNewMatch: {
                    showAddSheet = false
                    showNewMatch = true
                },
                onRecord: {
                    showAddSheet = false
                    if CameraRecorder.isAvailable {
                        showRecorder = true
                    } else {
                        intake.error = "Camera is not available on this device."
                    }
                },
                onImport: {
                    showAddSheet = false
                    showImporter = true
                }
            )
        }
        .sheet(isPresented: $showImporter) {
            VideoPicker(
                onPicked: { tempURL, suggestedName in
                    Task { await intake.add(tempURL: tempURL, suggestedName: suggestedName, isRecording: false) }
                },
                onFailed: { intake.error = "Couldn't import the video. Please try again." }
            )
        }
        .fullScreenCover(isPresented: $showRecorder) {
            CameraRecorder { tempURL in
                Task { await intake.add(tempURL: tempURL, suggestedName: nil, isRecording: true) }
            }
            .ignoresSafeArea()
        }
        .sheet(item: $detailsTarget) { target in
            MatchDetailsSheet(
                entry: target.entry,
                autoOpened: target.autoOpened,
                onSave: { title, description in
                    rally.localVideos.setDetails(
                        id: target.entry.id,
                        title: LocalVideoDetailsKt.normalizeTitle(raw: title),
                        description: LocalVideoDetailsKt.normalizeDescription(raw: description)
                    )
                }
            )
        }
        .onChange(of: intake.lastAddedId) { _, id in
            // The entry is already persisted by the time this fires, so a skipped
            // sheet never costs the video that was just imported or recorded.
            guard let id else { return }
            // Consumed unconditionally, before the lookup can fail: this fires only
            // on a change of id, so a signal left standing is never re-delivered —
            // it would wedge the auto-open for this import AND every one after it.
            intake.lastAddedId = nil
            // Read the registry, not `localEntries`: that mirror is filled by a
            // separate `for await` over the entries flow and still lags the add
            // that set this id, whereas get(id:) sees the value add() just wrote.
            guard let entry = rally.localVideos.get(id: id) else { return }
            // A video picked for a match already carries that match's name
            // (MatchTarget's title rode along on the INSERT), and videos.title is
            // insert-only, so there is nothing to ask here - MatchView owns that
            // pick and sends it straight to court marking instead.
            guard entry.scoreLogId == nil else { return }
            detailsTarget = MatchDetailsTarget(entry: entry, autoOpened: true)
        }
        .task {
            for await mode in rally.themePrefs.mode {
                themeMode = mode
            }
        }
    }

    /// Lands on the match page rather than the list once the board is done,
    /// because a match just created and scored in one sitting (New match ->
    /// Scoring, no match page underneath it yet) has nothing to pop back to. The
    /// chosen intent (if any) rides along so the match page can act on it once.
    /// Reassigns the SAME `createFlowTarget` binding the board itself is
    /// showing under, rather than popping it and pushing a separate one -
    /// see `CreateFlowDestination`'s own doc comment. Mirrors `AuthGate.kt`'s
    /// `Route.Scoring.onFinished`.
    private func onScoringFinished(_ scoreLogId: String) -> (AttachIntent?) -> Void {
        { intent in
            createFlowTarget = .finished(scoreLogId: scoreLogId, attach: intent)
        }
    }

    private func signOut() {
        Task { _ = try? await SwiftInteropKt.signOutOrMessage(rally.auth) }
    }

    private var homeScreen: some View {
        VStack(spacing: 0) {
            topBar
            if let error = intake.error {
                ErrorBanner(message: error)
            }
            Spacer().frame(height: 48)
            HeroTickerView(isPaused: drawerOpen)
                .padding(.horizontal, 24)
            Spacer()
            bottomControls
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Shuttl.bg)
        // Attached here, not on the ZStack that also contains the drawer:
        // scoped to the home screen alone so it never sits above the drawer's
        // own List and risks swallowing a row's swipe-to-delete or the list's
        // own scroll. Limited to the edge zone by checking where the drag
        // STARTED, not by clipping the gesture's hit area: edgeZoneWidth is
        // the mock's 26px "how close to the edge must this begin", the same
        // rule the drawer's own close-drag mirrors on its header.
        .gesture(edgeOpenDrag)
    }

    // The hamburger sits on the LEFT, not the right as the mock draws it: it
    // is both where the drawer's panel slides in from and where the edge-swipe
    // starts, and a hamburger opposite the edge its own panel opens from reads
    // as an unrelated control rather than the drawer's handle.
    private var topBar: some View {
        HStack {
            Button {
                withAnimation(.snappy(duration: 0.24)) { drawerOpen = true }
            } label: {
                Image(systemName: "line.3.horizontal")
                    .foregroundStyle(Shuttl.text)
            }
            .accessibilityLabel("Open matches")

            Spacer()

            Text("SHUTTL.")
                .shuttlType(ShuttlType.wordmark)
                .foregroundStyle(Shuttl.textHeading)

            Spacer()

            Menu {
                Button("Labels") { showLabels = true }
                Button("Sign out", action: signOut)
                Divider()
                Button(themeMode == .dark ? "Switch to light mode" : "Switch to dark mode") {
                    rally.themePrefs.toggle()
                }
                Divider()
                Text(versionLabel())
            } label: {
                Image(systemName: "ellipsis")
                    .foregroundStyle(Shuttl.text)
            }
            .accessibilityLabel("Menu")
        }
        .padding(.horizontal, 24)
        .padding(.top, 12)
    }

    private var bottomControls: some View {
        VStack(spacing: 12) {
            Button {
                showAddSheet = true
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "plus")
                    Text("Add new match")
                }
            }
            .buttonStyle(HomePillButtonStyle(background: Shuttl.accent, foreground: Shuttl.onAccent))
            // Composed of an Image and a Text, whose default accessibility
            // label would read as both concatenated in an unpredictable
            // order; stated explicitly so it is exactly "Add new match".
            .accessibilityLabel("Add new match")

            Button {
                // Unreachable: `.disabled(true)` below stops the tap before it
                // gets here.
            } label: {
                Text("Analytics")
            }
            .buttonStyle(HomePillButtonStyle(background: Shuttl.bgTertiary, foreground: Shuttl.text))
            // Analytics ships in Phase 3 (docs/plans/2026-09-03-home-and-
            // analytics-redesign-design.md). The pill is drawn per the mock now
            // so the layout is settled ahead of time, but there is no
            // destination to send it to yet. `.disabled(true)` alone would still
            // LOOK enabled - `HomePillButtonStyle` only dimmed on `isPressed`, so
            // the pill would visibly react to the tap and then do nothing, which
            // reads as a hang rather than an unbuilt feature - so the style
            // itself renders the disabled state (see its own `isEnabled` read).
            .disabled(true)
            .accessibilityHint("Coming soon")

            Button {
                withAnimation(.snappy(duration: 0.24)) { drawerOpen = true }
            } label: {
                Text("Swipe right for your matches")
                    .shuttlType(ShuttlType.bodyMedium)
                    .foregroundStyle(Shuttl.textSecondary)
                    // The touch target must clear iOS's 44pt minimum. The
                    // VStack's old `.padding(.bottom, 24)` moved in here so
                    // the total space from this label's top to the screen
                    // edge is unchanged - measured in the simulator, the
                    // label plus that 24pt is still short of 44pt (Archivo's
                    // natural line height at this size is ~14pt, not the
                    // ~20pt `.lineSpacing` would suggest, since `.lineSpacing`
                    // is inert on a single-line Text), so `frame(minHeight:)`
                    // makes up the rest, top-aligned so the glyphs do not
                    // move. `contentShape` extends the tap area to the full
                    // enlarged frame instead of just the glyph bounds.
                    .padding(.bottom, 24)
                    .frame(minHeight: 44, alignment: .top)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 24)
    }

    private var edgeOpenDrag: some Gesture {
        DragGesture()
            .onEnded { value in
                guard value.startLocation.x <= DrawerDragMath.edgeZoneWidth else { return }
                guard DrawerDragMath.shouldOpen(
                    translation: value.translation.width, velocity: value.velocity.width
                ) else { return }
                withAnimation(.snappy(duration: 0.24)) { drawerOpen = true }
            }
    }
}

/// Home's two 60pt pills. Not `PrimaryButtonStyle`: that style sizes itself
/// from padding plus the font's own line height, which does not reliably land
/// on 60 - the design's one exact metric for these two buttons - so the height
/// is set directly instead.
private struct HomePillButtonStyle: ButtonStyle {
    let background: Color
    let foreground: Color
    // A custom ButtonStyle does not dim itself on `.disabled(true)` the way a
    // system style does - `configuration` carries only `isPressed`, nothing
    // about enablement - so a disabled pill needs to read this directly or it
    // renders identically to an enabled one and just silently swallows taps.
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .shuttlType(ShuttlType.titleLarge)
            .foregroundStyle(foreground.opacity(isEnabled ? 1 : 0.4))
            .frame(maxWidth: .infinity)
            .frame(height: 60)
            .background(background.opacity(isEnabled ? (configuration.isPressed ? 0.8 : 1) : 0.4))
            .clipShape(Capsule())
    }
}

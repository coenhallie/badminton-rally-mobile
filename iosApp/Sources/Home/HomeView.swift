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

/// The app's tab host: Matches, Analytics and Settings inside one `TabView`,
/// which sits at this stack's root so every push (the scoring board above
/// all) covers the bar with no per-screen code needed to hide it. Owns the
/// `NavigationStack` and every destination `MatchesList` (formerly
/// `ClipListView`) used to register itself - `MatchesList` reports taps
/// upward through closures instead, because it no longer has a stack of its
/// own to push onto once it lives inside a tab.
struct HomeView: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    /// The on-device pipeline, or nil in a build with no models staged. Owned by
    /// `RootView`; Home only routes it to the two screens that offer a run.
    let localAnalysis: LocalAnalysisRunner?

    /// The selected tab. The bar lives inside this stack's root, so every push
    /// covers it (the scoring board needs no code to hide it), and one stack
    /// keeps `CreateFlowDestination`'s single binding and the one mounted
    /// parent the match-list feed and the run watcher rely on.
    @State private var selectedTab: HomeTab = .matches
    /// Any sheet or dialog MatchesList has open over the tabs.
    @State private var listOverlayOpen = false
    @State private var showAddSheet = false
    @State private var showLabels = false
    @State private var showNewMatch = false
    @State private var showRecorder = false
    @State private var showImporter = false
    @State private var intake: LocalVideoIntake
    /// One model for the whole app, made here rather than inside the screens that
    /// read it. Its `localEntries` is documented as the single source of truth
    /// for every video on this phone, which a second instance would quietly make
    /// false; and `start()` spawns `for await` loops that never return and hold
    /// the model alive, so a model created inside a pushed view would leak one
    /// full set of them on every push. Both the Matches tab and the Analytics
    /// tab call `start()`, which is idempotent, so the loops are still started
    /// exactly once no matter which tab mounts first.
    @State private var listModel: ClipListModel
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
    /// Where an on-device run's own output is reached from the Matches tab:
    /// the rallies it cut, and the heatmap of the track it kept. Owned here
    /// like every other destination the list reports upward.
    @State private var localClipsRoute: LocalClipsRoute? = nil
    @State private var analyticsDetailRoute: AnalyticsDetailRoute? = nil
    @State private var createFlowTarget: CreateFlowDestination? = nil

    /// Device runs already announced, so a finished one opens Analytics once.
    /// See `announceFinishedRuns`, which owns the rule both platforms follow.
    @State private var announcedRuns: Set<String> = []

    init(rally: RallyApp, analyze: AnalyzeCoordinator, localAnalysis: LocalAnalysisRunner?) {
        self.rally = rally
        self.analyze = analyze
        self.localAnalysis = localAnalysis
        _intake = State(initialValue: LocalVideoIntake(rally: rally))
        _listModel = State(initialValue: ClipListModel(
            rally: rally, analyze: analyze,
            // The runner's own live state, not a copy fed through a screen's
            // `.onChange`: a delete's guard must see a run started on whichever
            // screen is on top right now, not only the Matches tab's.
            liveDeviceRuns: { [localAnalysis] in localAnalysis?.deviceRuns ?? [:] }
        ))
    }

    var body: some View {
        NavigationStack {
            TabView(selection: $selectedTab) {
                MatchesTabView(
                    rally: rally, analyze: analyze, intake: intake, model: listModel,
                    heroPaused: selectedTab != .matches || !nothingAbove,
                    onMatchTap: { matchRoute = $0 },
                    onCourtMarking: { courtMarkingRoute = $0 },
                    onLocalPlayer: { localPlayerRoute = $0 },
                    onLocalClips: { localClipsRoute = $0 },
                    onAnalyticsDetail: { analyticsDetailRoute = $0 },
                    onOverlayChange: { listOverlayOpen = $0 },
                    onAdd: { showAddSheet = true }
                )
                .tabItem { tabLabel(HomeCopy.shared.TAB_MATCHES, systemImage: "rectangle.split.1x2", tab: .matches) }
                .tag(HomeTab.matches)

                AnalyticsListView(
                    rally: rally, analyze: analyze, model: listModel, localAnalysis: localAnalysis,
                    onCourtMarking: { courtMarkingRoute = $0 },
                    onDetail: { analyticsDetailRoute = $0 }
                )
                .tabItem { tabLabel(HomeCopy.shared.TAB_ANALYTICS, systemImage: "chart.bar", tab: .analytics) }
                .tag(HomeTab.analytics)

                SettingsView(rally: rally, onLabels: { showLabels = true })
                    .tabItem { tabLabel(HomeCopy.shared.TAB_SETTINGS, systemImage: "slider.horizontal.3", tab: .settings) }
                    .tag(HomeTab.settings)
            }
            .tint(Shuttl.analysisGlyph)
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(item: $courtMarkingRoute) { route in
                CourtMarkingView(
                    rally: rally, analyze: analyze,
                    localAnalysis: localAnalysis, entryId: route.entryId
                )
            }
            .navigationDestination(isPresented: $showLabels) {
                LabelsView(rally: rally)
            }
            .navigationDestination(item: $matchRoute) { route in
                MatchView(
                    rally: rally, analyze: analyze,
                    localAnalysis: localAnalysis, route: route
                )
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
                        rally: rally, analyze: analyze, localAnalysis: localAnalysis,
                        route: MatchRoute(scoreLogId: scoreLogId, videoId: nil, attach: attach)
                    )
                }
            }
            .navigationDestination(item: $localPlayerRoute) { route in
                LocalPlayerView(
                    rally: rally, analyze: analyze,
                    localAnalysis: localAnalysis, entryId: route.entryId
                )
            }
            .navigationDestination(item: $localClipsRoute) { route in
                LocalClipsView(localAnalysis: localAnalysis, entryId: route.entryId)
            }
            // The same screen the Matches tab and the Analytics tab both push,
            // not two: both routes to one heatmap render `AnalyticsDetailView`,
            // so there is one resolution of which track to draw rather than
            // two. `fallbackTitle` because both rows carry a title now that
            // Analytics pushes this too, not only the Matches tab's list.
            .navigationDestination(item: $analyticsDetailRoute) { route in
                AnalyticsDetailView(
                    rally: rally, localAnalysis: localAnalysis, entryId: route.entryId,
                    fallbackTitle: route.title
                )
            }
        }
        // Device runs and stored results, fed into `listModel` from here and
        // nowhere else: this `NavigationStack` is the common parent of every
        // tab, and it stays mounted for as long as the app does, regardless of
        // which destination above is pushed on top of it. See `MatchListFeed`.
        .feedsMatchList(model: listModel, localAnalysis: localAnalysis)
        // A CLOUD run that lands does the same. It ends by retiring its entry,
        // so without this the coach watching Home saw the row vanish and
        // nothing else - no result, and nothing saying where it had gone. The
        // two sets are unioned into one announcement so a video analysed both
        // ways announces once. Mirrors androidApp's `combine` above its NavHost.
        .task {
            for await finishedInCloud in analyze.finishedRuns {
                announce(cloudFinished: Set(finishedInCloud))
            }
        }
        // A device run that lands takes the coach to the Analytics list.
        // androidApp does the same from above its NavHost; `announceFinishedRuns`
        // in shared is the rule both follow, and a change to when this fires is a
        // change in both places.
        //
        // Keyed on `settledRuns`, never on `states`, for the reason that
        // property's own doc gives: `@Observable` tracks a property, so reading
        // `states` from a body - and the `of:` expression is body - subscribes
        // Home to every per-frame write and redraws it at the rate of the
        // progress bar. `settledRuns` changes twice per run. Picking the
        // finished ones back out does need `states`, but a read inside the
        // action closure is not tracked.
        .onChange(of: localAnalysis?.settledRuns) { _, _ in
            announce(cloudFinished: Set(analyze.finishedRuns.value as? [String] ?? []))
        }
        // Inside the stack, because every destination that carries the indicator
        // is inside it and the routes this dismisses are Home's.
        .environment(\.backgroundWorkClick, goToMatches)
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
        .sheet(item: $detailsTarget, onDismiss: {
            // However the sheet went away - saved, skipped, or swiped down -
            // the video it named is the new top row on the Matches tab (the
            // list sorts a video on when it was added, newest first). The Add
            // button that starts a record or import is only on that tab, so
            // the coach is normally there already and this changes nothing;
            // it exists for the one case where they are not, so the promise
            // that the named video is on screen still holds. This binding is
            // the auto-open case only (see its declaration), so the row
            // menu's own "Edit details" does not come through here.
            selectedTab = .matches
        }) { target in
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
            // on a change of id, so a signal left standing is never re-delivered -
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
    }

    /// A tab's icon and name, outlined unless it is the selected tab. The
    /// bar fills every symbol on its own, which drew all three tabs solid; the
    /// mock and Android fill only the selected one. `slider.horizontal.3` has no
    /// fill form, so Settings keeps its outline either way, as on Android.
    private func tabLabel(_ title: String, systemImage: String, tab: HomeTab) -> some View {
        Label(title, systemImage: systemImage)
            .environment(\.symbolVariants, selectedTab == tab ? .fill : .none)
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

    /// Whether nothing covers the tabs: no push, no sheet, no list dialog.
    /// Every route and sheet this view owns is listed; a new one must be added,
    /// or a finished run switches tabs under it. The tab itself is checked by
    /// `isHomeFront`, shared with androidApp.
    private var nothingAbove: Bool {
        !showLabels && !showNewMatch && !showAddSheet && !showRecorder && !showImporter
            && courtMarkingRoute == nil && matchRoute == nil && localPlayerRoute == nil
            && localClipsRoute == nil && analyticsDetailRoute == nil
            && createFlowTarget == nil && detailsTarget == nil && !listOverlayOpen
    }

    /// One announcement for both pipelines: a run that lands, on-device or in
    /// the cloud, takes the coach to the Analytics tab.
    ///
    /// `announceFinishedRuns` in shared owns the rule - it gates on
    /// `isHomeFront` (the Matches tab, nothing above) and keeps its own seen
    /// set, so a run that landed while the coach was elsewhere does not ambush
    /// them later. This only gathers the two sets of finished ids. Reading
    /// `localAnalysis?.states` here rather than in a body is deliberate: a
    /// tracked read would redraw this view at the progress bar's rate. See
    /// the `settledRuns` comment above.
    private func announce(cloudFinished: Set<String>) {
        let onDevice = (localAnalysis?.states ?? [:]).compactMap { id, state -> String? in
            if case .done = state { return id } else { return nil }
        }
        let announcement = RunAnnouncementKt.announceFinishedRuns(
            finished: Set(onDevice).union(cloudFinished),
            seen: announcedRuns,
            onHome: HomeTabKt.isHomeFront(tab: selectedTab, nothingAbove: nothingAbove)
        )
        announcedRuns = announcement.seen
        if announcement.openAnalytics { selectedTab = .analytics }
    }

    /// Where the chrome indicator sends a coach: the Matches tab, with nothing
    /// else on screen.
    ///
    /// The run it points at is a row on that tab, not wherever the coach
    /// currently is, so switching tabs is not enough - every route and sheet
    /// this view owns has to be dismissed too, the same list `nothingAbove`
    /// checks. The indicator is reachable from any of them, and without this
    /// a tap from two screens deep would silently do nothing.
    private func goToMatches() {
        courtMarkingRoute = nil
        matchRoute = nil
        localPlayerRoute = nil
        localClipsRoute = nil
        analyticsDetailRoute = nil
        createFlowTarget = nil
        showLabels = false
        showNewMatch = false
        selectedTab = .matches
    }
}

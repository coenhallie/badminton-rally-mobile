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

/// The signed-in app: one `NavigationStack` with the match list
/// (`MatchesScreen`) at its root and every other screen pushed over it,
/// Settings included. Owns every destination `MatchesList` (formerly
/// `ClipListView`) used to register itself - `MatchesList` reports taps
/// upward through closures instead, so the destinations live on the one view
/// that stays mounted under every push. Also hears finished runs, and says so
/// on the list's toast.
struct HomeView: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    /// The on-device pipeline, or nil in a build with no models staged. Owned by
    /// `RootView`; Home only routes it to the two screens that offer a run.
    let localAnalysis: LocalAnalysisRunner?

    /// Any sheet or dialog MatchesList has open over the list.
    @State private var listOverlayOpen = false
    @State private var showAddSheet = false
    @State private var showSettings = false
    @State private var showNewMatch = false
    @State private var showRecorder = false
    @State private var showImporter = false
    @State private var intake: LocalVideoIntake
    /// One model for the whole app, made here rather than inside the screens that
    /// read it. Its `localEntries` is documented as the single source of truth
    /// for every video on this phone, which a second instance would quietly make
    /// false; and `start()` spawns `for await` loops that never return and hold
    /// the model alive, so a model created inside a pushed view would leak one
    /// full set of them on every push. `start()` runs from the list's `.task`
    /// and from the match page's (which finds its row here). That is not
    /// idempotent: each call runs its own set of the five loops, and only the
    /// initial network refresh is latched to once per model. What keeps it to
    /// one set at a time is that a covered view's `.task` is cancelled: a push
    /// cancels the list's under the match page. The two overlap only for the
    /// length of a push or pop transition.
    @State private var listModel: ClipListModel
    /// The auto-open case only: straight after a record/import that isn't for
    /// an existing match, so the coach can name it. The row menu's own "Edit
    /// details" keeps a separate, `MatchesList`-owned target for the same
    /// sheet - the two never need to agree on one value at the same time.
    @State private var detailsTarget: MatchDetailsTarget? = nil

    // Every destination MatchesList used to carry, now owned here. Two of
    // them (courtMarking, matchTap) are still triggered from inside the list,
    // only now through a closure instead of an owned binding or a
    // `NavigationLink(value:)` reaching up through the environment. The rest
    // (new match, the create-and-finish flow) had no trigger left once the
    // list's toolbar was deleted, so they moved here wholesale, state and all.
    @State private var courtMarkingRoute: CourtMarkingRoute? = nil
    /// Deliberately separate from `createFlowTarget`: a plain row tap only
    /// ever needs `MatchView`, never the board, so folding this into the
    /// create-and-finish binding would give it a case it never uses. Keep the
    /// two apart - `CreateFlowDestination`'s doc comment is about racing pops
    /// and pushes on DIFFERENT bindings, not about how many bindings exist in
    /// total, and this route and that one are never on screen at once.
    @State private var matchRoute: MatchRoute? = nil
    @State private var createFlowTarget: CreateFlowDestination? = nil

    /// Runs already seen finished, on-device or in the cloud, so each one is
    /// announced at most once. See `announceFinishedRuns`, which owns the rule
    /// both platforms follow.
    @State private var announcedRuns: Set<String> = []
    /// What the list's toast says, when anything.
    @State private var toast: ToastMessage? = nil
    /// The runs the toast on screen is about, so a second run landing while
    /// it shows joins it ("2 matches are analyzed") rather than replacing it
    /// and losing the first. Read only while a toast shows; see `announce`.
    @State private var toastRuns: Set<String> = []
    /// Whether the app is in front. See `nothingAbove`.
    @Environment(\.scenePhase) private var scenePhase

    init(rally: RallyApp, analyze: AnalyzeCoordinator, localAnalysis: LocalAnalysisRunner?) {
        self.rally = rally
        self.analyze = analyze
        self.localAnalysis = localAnalysis
        _intake = State(initialValue: LocalVideoIntake(rally: rally))
        _listModel = State(initialValue: ClipListModel(
            rally: rally, analyze: analyze,
            // The runner's own live state, not a copy fed through a screen's
            // `.onChange`: a delete's guard must see a run started on whichever
            // screen is on top right now, not only the list's.
            liveDeviceRuns: { [localAnalysis] in localAnalysis?.deviceRuns ?? [:] }
        ))
    }

    var body: some View {
        NavigationStack {
            MatchesScreen(
                rally: rally, analyze: analyze, intake: intake, model: listModel,
                heroPaused: !nothingAbove,
                onMatchTap: { matchRoute = $0 },
                onCourtMarking: { courtMarkingRoute = $0 },
                onOverlayChange: { listOverlayOpen = $0 },
                onAdd: { showAddSheet = true },
                onSettings: { showSettings = true },
                toast: $toast
            )
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(item: $courtMarkingRoute) { route in
                CourtMarkingView(
                    rally: rally, analyze: analyze,
                    localAnalysis: localAnalysis, entryId: route.entryId
                )
            }
            .navigationDestination(isPresented: $showSettings) {
                SettingsView(rally: rally)
            }
            .navigationDestination(item: $matchRoute) { route in
                MatchView(
                    rally: rally, analyze: analyze,
                    localAnalysis: localAnalysis, listModel: listModel, route: route
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
                        rally: rally, analyze: analyze, localAnalysis: localAnalysis, listModel: listModel,
                        route: MatchRoute(scoreLogId: scoreLogId, videoId: nil, attach: attach)
                    )
                }
            }
        }
        // Device runs and stored results, fed into `listModel` from here and
        // nowhere else: this `NavigationStack` is the common parent of every
        // screen, and it stays mounted for as long as the app does, regardless
        // of which destination is pushed on top of it. See `MatchListFeed`.
        .feedsMatchList(model: listModel, localAnalysis: localAnalysis)
        // A CLOUD run that lands is announced like a device run. It keeps its
        // entry, and without this its end showed on the list only as the row's
        // status line changing - nothing saying the result was ready or where
        // to find it. The two sets are unioned into one announcement so
        // a video analyzed both ways announces once. Mirrors androidApp's
        // `combine` above its NavHost.
        //
        // Here, on the stack, rather than on the list: a push cancels the
        // list's `.task`s, and the seen-set has to keep up while the coach is
        // elsewhere, or a run that landed then would announce itself on their
        // return.
        .task {
            for await finishedInCloud in analyze.finishedRuns {
                announce(cloudFinished: finishedInCloud)
            }
        }
        // A device run that lands is announced on the list's toast, only
        // while the list is the front screen with nothing above it
        // (`nothingAbove`). Nothing navigates on its own. androidApp does the
        // same from above its NavHost; `announceFinishedRuns` in shared is the
        // rule both follow, and a change to when this fires is a change in
        // both places.
        //
        // Keyed on `settledRuns`, never on `states`, for the reason that
        // property's own doc gives: `@Observable` tracks a property, so reading
        // `states` from a body - and the `of:` expression is body - subscribes
        // Home to every per-frame write and redraws it at the rate of the
        // progress bar. `settledRuns` changes twice per run. Picking the
        // finished ones back out does need `states`, but a read inside the
        // action closure is not tracked.
        .onChange(of: localAnalysis?.settledRuns) { _, _ in
            // The cloud set as it stands, so a device run settling does not
            // drop a cloud run that finished earlier from the union.
            announce(cloudFinished: analyze.finishedRuns.value)
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

    /// Whether nothing covers the list and the app is in front: no push, no
    /// sheet, no list dialog, and the scene active. Every route and sheet
    /// this view owns is listed; a new one must be added, or a finished run
    /// is announced on a toast nobody can see (and marked seen, so never
    /// again). `detailsTarget` counts: a video just added is waiting to be
    /// named, and a View offered then pulls the other way.
    ///
    /// The scene phase is androidApp's RESUMED check. A cloud run can land in
    /// the seconds after the coach leaves the app, and its toast would time
    /// out unseen. The seen-set rule does not change for it: a run that lands
    /// while the app is in the background is marked seen and does not
    /// announce itself on return, as for any run that lands while the coach
    /// is elsewhere. Also pauses the empty state's hero ticker.
    private var nothingAbove: Bool {
        scenePhase == .active
            && !showSettings && !showNewMatch && !showAddSheet && !showRecorder && !showImporter
            && courtMarkingRoute == nil && matchRoute == nil
            && createFlowTarget == nil && detailsTarget == nil && !listOverlayOpen
    }

    /// One announcement for both pipelines: a run that lands, on-device or in
    /// the cloud, is said on the list's toast, with a View that opens its
    /// match. The app does not move on its own: opening the match's page
    /// would take the screen from under the coach mid-scroll.
    ///
    /// `runToastUpdate` decides which runs, and `announceFinishedRuns` in
    /// shared under it owns the rule - it gates on the list being in front
    /// with nothing above it and keeps its own seen set, so a run that landed
    /// while the coach was elsewhere does not ambush them later. This reads
    /// the inputs and builds the toast.
    ///
    /// Reading `localAnalysis?.states` here rather than in a body is
    /// deliberate: a tracked read would redraw this view at the progress
    /// bar's rate. See the `settledRuns` comment above.
    private func announce(cloudFinished: Set<String>) {
        let update = runToastUpdate(
            deviceStates: localAnalysis?.states ?? [:],
            cloudFinished: cloudFinished,
            seen: announcedRuns,
            onHome: nothingAbove,
            // `toastRuns` is stale once the toast has gone.
            showing: toast == nil ? nil : toastRuns
        )
        announcedRuns = update.seen
        // Built from the list as it is now. Nil when none of the runs is a
        // row any more (a match deleted while its run was finishing).
        guard let ids = update.toastRuns,
              let message = FinishedRunMessageKt.finishedRunMessage(ids: ids, list: listModel.matchList)
        else { return }
        toastRuns = ids
        let item = message.item
        toast = ToastMessage(
            text: message.text,
            actionLabel: item == nil ? nil : MatchPageCopy.shared.VIEW,
            action: item.map { item in { openAfterRun(item) } }
        )
    }

    /// A finished run's View: its match, on what the run made.
    ///
    /// The row as it is at the tap, not as it was when the run landed: the
    /// announcement can come before the list has caught up (a cloud run's
    /// stored track lands after its finish), and the frozen row would then
    /// have no Analysis to open on. Falls back to the frozen row if the match
    /// has left the list; the page says it is gone. Mirrors androidApp's
    /// HomeScreen.
    private func openAfterRun(_ landed: MatchListItem) {
        let item = listModel.matchList.matchPageItem(
            scoreLogId: landed.scoreLogId, videoId: landed.videoId, entryId: landed.entryId
        ) ?? landed
        matchRoute = MatchRoute(
            scoreLogId: item.scoreLogId, videoId: item.videoId, entryId: item.entryId,
            section: MatchPageSectionKt.sectionAfterRun(item: item)
        )
    }

    /// Where the chrome indicator sends a coach: the match list, with nothing
    /// else on screen.
    ///
    /// The run it points at is a row on that list, not wherever the coach
    /// currently is, so every push this view owns is popped (Labels goes with
    /// Settings, which pushed it). The indicator is on every pushed screen's
    /// bar, and without this a tap from two screens deep would silently do
    /// nothing.
    ///
    /// Pushes only, not everything `nothingAbove` checks: sheets need no
    /// clearing, because `BackgroundWorkAction` never appears inside a sheet,
    /// so no tap on it can come from under one.
    private func goToMatches() {
        courtMarkingRoute = nil
        matchRoute = nil
        createFlowTarget = nil
        showSettings = false
        showNewMatch = false
    }
}

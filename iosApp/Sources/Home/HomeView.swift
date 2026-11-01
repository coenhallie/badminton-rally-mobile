import SwiftUI
import Shared

/// The signed-in app: one `NavigationStack` with the Players screen at its
/// root and every other screen pushed over it through one path of
/// `HomeRoute`s (see there for why a path and not a binding per screen). Owns
/// every destination the list screens open, which report taps upward through
/// closures, so the destinations live on the one view that stays mounted
/// under every push.
///
/// Also the app's announcements host (Rulings 12 and 15): a finished run, a
/// record or import that failed, the match list's own failure, a player save
/// that failed, and the auto-shown dialog of a standalone video whose
/// analysis failed reach the coach wherever they are. The messages go on the
/// root toast, which Players and All matches draw, queued until one of them
/// is in front; the dialog shows over any screen but the scoring board.
/// Mirrors androidApp's AuthGate and RootAnnouncementsHost.
struct HomeView: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    /// The on-device pipeline, or nil in a build with no models staged. Owned by
    /// `RootView`; Home only routes it to the two screens that offer a run.
    let localAnalysis: LocalAnalysisRunner?

    /// Everything pushed over Players, bottom first. Every move is a single
    /// reassignment: see `HomePath`.
    @State private var path: [HomeRoute] = []
    /// Any sheet or dialog MatchesList (on All matches) has open.
    @State private var listOverlayOpen = false
    /// The add-player sheet on Players.
    @State private var playersOverlayOpen = false
    @State private var showAddSheet = false
    @State private var showRecorder = false
    @State private var showImporter = false
    @State private var intake: LocalVideoIntake
    /// One model for the whole app, made here rather than inside the screens that
    /// read it. Its `localEntries` is documented as the single source of truth
    /// for every video on this phone, which a second instance would quietly make
    /// false; and `start()` spawns `for await` loops that never return and hold
    /// the model alive, so a model created inside a pushed view would leak one
    /// full set of them on every push. `start()` runs from the `.task` of each
    /// screen that reads the list: Players (its rows' details), All matches and
    /// the match page (which finds its row here). That is not idempotent: each
    /// call runs its own set of loops, and only the initial network refresh is
    /// latched to once per model. What keeps it to one set at a time is that a
    /// covered view's `.task` is cancelled: a push cancels the screen's under it.
    /// The two overlap only for the length of a push or pop transition.
    @State private var listModel: ClipListModel
    /// The coach's players, one instance for the same reasons as `listModel`.
    @State private var playersModel: PlayersModel
    /// The auto-open case only: straight after a record/import that isn't for
    /// an existing match, so the coach can name it. Shown over whatever screen
    /// is in front. The row menu's own "Edit details" keeps a separate,
    /// `MatchesList`-owned target for the same sheet - the two never need to
    /// agree on one value at the same time.
    @State private var detailsTarget: MatchDetailsTarget? = nil

    /// Runs already seen finished, on-device or in the cloud, so each one is
    /// announced at most once. See `announceFinishedRuns`, which owns the rule
    /// both platforms follow.
    @State private var announcedRuns: Set<String> = []
    /// What the root toast says, when anything. Drawn by Players and All
    /// matches.
    @State private var toast: ToastMessage? = nil
    /// Messages waiting for `toast`, oldest first: one shows at a time, and
    /// only while a list screen is in front with nothing above it. See
    /// `deliverToast`.
    @State private var toastQueue: [ToastMessage] = []
    /// The runs the run toast names, and that toast's id while it is showing or
    /// waiting, so a second run landing joins it ("2 matches are analyzed")
    /// rather than queuing a second toast. See `announce`.
    @State private var toastRuns: Set<String> = []
    @State private var runToastId: UUID? = nil
    /// A standalone video whose analysis failed and whose result the coach has
    /// not seen: the dialog shown for it. See `resultDialogShown`.
    @State private var resultEntry: LocalVideoEntry? = nil
    /// Whether the app is in front. See `nothingAbove`.
    @Environment(\.scenePhase) private var scenePhase
    /// Where the scoring board says it is on screen, from either place that
    /// pushes it. Optional so a Home built outside `RootView` still draws.
    @Environment(KeepAwake.self) private var keepAwake: KeepAwake?

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
        _playersModel = State(initialValue: PlayersModel(rally: rally))
    }

    var body: some View {
        // Split into stages rather than one chain, as MatchesList is: one
        // chain this long is past what the type checker settles in time.
        announcements(presentations(stack))
    }

    private var stack: some View {
        NavigationStack(path: $path) {
            PlayersScreen(
                model: playersModel,
                listModel: listModel,
                // The Player page is not built yet; a row opens nothing until
                // it is.
                onOpenPlayer: { _ in },
                onAllMatches: { path.append(.allMatches) },
                onAdd: { showAddSheet = true },
                onSettings: { path.append(.settings) },
                onOverlayChange: { playersOverlayOpen = $0 },
                toast: $toast
            )
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: HomeRoute.self, destination: destination)
        }
        // Device runs and stored results, fed into `listModel` from here and
        // nowhere else: this `NavigationStack` is the common parent of every
        // screen, and it stays mounted for as long as the app does, regardless
        // of which destination is pushed on top of it. See `MatchListFeed`.
        .feedsMatchList(model: listModel, localAnalysis: localAnalysis)
        // Inside the stack, because every destination that carries the indicator
        // is inside it and the path this rewrites is Home's.
        .environment(\.backgroundWorkClick, goToAllMatches)
    }

    @ViewBuilder
    private func destination(_ route: HomeRoute) -> some View {
        switch route {
        case .allMatches:
            MatchesScreen(
                rally: rally, analyze: analyze, intake: intake, model: listModel,
                heroPaused: !nothingAbove,
                onMatchTap: { path.append(.match($0)) },
                onCourtMarking: { path.append(.courtMarking($0)) },
                onOverlayChange: { listOverlayOpen = $0 },
                toast: $toast
            )
        case .match(let matchRoute):
            MatchView(
                rally: rally, analyze: analyze,
                localAnalysis: localAnalysis, listModel: listModel, route: matchRoute
            )
        case .courtMarking(let markingRoute):
            CourtMarkingView(
                rally: rally, analyze: analyze,
                localAnalysis: localAnalysis, entryId: markingRoute.entryId
            )
        case .settings:
            SettingsView(rally: rally)
        case .newMatch:
            NewMatchView(rally: rally) { id in
                // Straight to the board, in the form's place, not back to
                // Players and not to the record: creating a match courtside
                // means being about to score it.
                path = HomePath.replacing(.newMatch, with: .scoring(scoreLogId: id), in: path)
            }
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

    /// The Add sheet and what it opens, and the auto-opened details sheet.
    private func presentations(_ content: some View) -> some View {
        content
        .sheet(isPresented: $showAddSheet) {
            AddMatchSheet(
                onNewMatch: {
                    showAddSheet = false
                    path.append(.newMatch)
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

    /// The root announcements: finished runs, failures, and the failed-run
    /// dialog.
    private func announcements(_ content: some View) -> some View {
        content
        // A CLOUD run that lands is announced like a device run. It keeps its
        // entry, and without this its end showed on the list only as the row's
        // status line changing - nothing saying the result was ready or where
        // to find it. The two sets are unioned into one announcement so
        // a video analyzed both ways announces once. Mirrors androidApp's
        // `combine` above its NavHost.
        //
        // Here, on the stack, rather than on a list screen: a push cancels the
        // screen's `.task`s, and the seen-set has to keep up while the coach is
        // elsewhere, or a run that landed then would announce itself on their
        // return.
        .task {
            for await finishedInCloud in analyze.finishedRuns {
                announce(cloudFinished: finishedInCloud)
            }
        }
        // A device run that lands is announced on the root toast, only while a
        // list screen is in front with nothing above it (`nothingAbove`).
        // Nothing navigates on its own. androidApp does the same from above
        // its NavHost; `announceFinishedRuns` in shared is the rule both
        // follow, and a change to when this fires is a change in both places.
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
        // Record and import failures, the list's own failure ("Couldn't
        // refresh matches", a delete the server refused) and a player save
        // that failed: each taken off its owner as it is queued, so it is said
        // once and never turns up again on a screen opened later.
        .onChange(of: intake.error) { _, error in
            guard let error else { return }
            intake.error = nil
            post(error)
        }
        .onChange(of: listModel.error) { _, error in
            guard let error else { return }
            listModel.error = nil
            post(error)
        }
        .onChange(of: playersModel.errorMessage) { _, error in
            guard let error else { return }
            playersModel.errorShown()
            post(error)
        }
        .onChange(of: toast) { _, _ in deliverToast() }
        .onChange(of: nothingAbove) { _, _ in deliverToast() }
        // A standalone video whose analysis failed, once per failure: its
        // dialog comes up wherever the coach is. `resultSeen` is persisted on
        // the entry, so a dismissal survives navigation and relaunch; a later
        // retry that fails again resets the flag and shows it anew. A video
        // claimed by a match fails on its match's row and page instead, which
        // say so and offer Retry. On the stack, not on a screen, so it is
        // heard whichever screen is in front.
        .task {
            for await entries in rally.localVideos.entries {
                if resultEntry == nil {
                    resultEntry = entries.first { $0.scoreLogId == nil && $0.stage == .failed && !$0.resultSeen }
                }
            }
        }
        .alert(
            (resultEntry?.failureMessage ?? "").localizedCaseInsensitiveContains("no rallies")
                ? "No rallies found" : "Analysis failed",
            isPresented: Binding(
                get: { resultDialogShown },
                set: { if !$0 { resultEntry = nil } }
            ),
            presenting: resultEntry
        ) { entry in
            Button("Retry") {
                rally.localVideos.acknowledgeResult(id: entry.id)
                resultEntry = nil
                analyzeEntry(entry.id)
            }
            Button("Close", role: .cancel) {
                rally.localVideos.acknowledgeResult(id: entry.id)
                resultEntry = nil
            }
        } message: { entry in
            Text(entry.failureMessage ?? "Unknown error")
        }
    }

    /// Lands on the match page rather than the list once the board is done,
    /// because a match just created and scored in one sitting (New match ->
    /// Scoring, no match page underneath it yet) has nothing to pop back to.
    /// The chosen intent (if any) rides along so the match page can act on it
    /// once. Replaces the board's own entry in place rather than popping it
    /// and pushing another: see `HomeRoute`. Mirrors `AuthGate.kt`'s
    /// `Route.Scoring.onFinished`.
    private func onScoringFinished(_ scoreLogId: String) -> (AttachIntent?) -> Void {
        { intent in
            path = HomePath.replacing(
                .scoring(scoreLogId: scoreLogId),
                with: .finished(scoreLogId: scoreLogId, attach: intent),
                in: path
            )
        }
    }

    /// Whether a list screen is in front with nothing over it and the app is
    /// in front: Players or All matches on top of the path, no sheet, no list
    /// dialog, and the scene active. Every sheet and dialog this view knows of
    /// is listed; a new one must be added, or a finished run is announced on a
    /// toast nobody can see (and marked seen, so never again). `detailsTarget`
    /// counts: a video just added is waiting to be named, and a View offered
    /// then pulls the other way.
    ///
    /// The scene phase is androidApp's RESUMED check. A cloud run can land in
    /// the seconds after the coach leaves the app, and its toast would time
    /// out unseen. The seen-set rule does not change for it: a run that lands
    /// while the app is in the background is marked seen and does not
    /// announce itself on return, as for any run that lands while the coach
    /// is elsewhere. Also pauses the empty state's hero ticker.
    private var nothingAbove: Bool {
        scenePhase == .active
            && HomePath.listInFront(path)
            && !showAddSheet && !showRecorder && !showImporter
            && detailsTarget == nil && !listOverlayOpen && !playersOverlayOpen
            && !resultDialogShown
    }

    /// Whether the failed-run dialog is up: there is one to show, and nothing
    /// it must wait for. It waits while the scoring board is in front (Ruling
    /// 15): a dialog arriving under a coach's thumb mid-rally would take the
    /// point's tap. It also waits under a sheet this view or a list screen has
    /// open, since iOS shows one presentation at a time; a sheet the match
    /// page opens itself is not known here.
    private var resultDialogShown: Bool {
        resultEntry != nil
            && keepAwake?.boardsOnScreen.isEmpty != false
            && !showAddSheet && !showRecorder && !showImporter
            && detailsTarget == nil && !listOverlayOpen && !playersOverlayOpen
    }

    /// Queues `text` for the root toast.
    private func post(_ text: String) {
        toastQueue.append(ToastMessage(text: text, actionLabel: nil, action: nil))
        deliverToast()
    }

    /// Shows the oldest waiting message, once nothing is showing and a list
    /// screen is in front with nothing above it. Not sooner: a toast set while
    /// another screen is on top would sit in a list screen that cannot be
    /// seen, and time out there.
    private func deliverToast() {
        guard toast == nil, nothingAbove, !toastQueue.isEmpty else { return }
        toast = toastQueue.removeFirst()
    }

    /// Whether the run toast is showing or waiting its turn.
    private var runToastPending: Bool {
        guard let id = runToastId else { return false }
        return toast?.id == id || toastQueue.contains { $0.id == id }
    }

    /// One announcement for both pipelines: a run that lands, on-device or in
    /// the cloud, is said on the root toast, with a View that opens its match.
    /// The app does not move on its own: opening the match's page would take
    /// the screen from under the coach mid-scroll.
    ///
    /// `runToastUpdate` decides which runs, and `announceFinishedRuns` in
    /// shared under it owns the rule - it gates on a list being in front with
    /// nothing above it and keeps its own seen set, so a run that landed
    /// while the coach was elsewhere does not ambush them later. This reads
    /// the inputs and builds the toast. A run toast already showing or waiting
    /// is replaced where it stands, naming every run; otherwise the new one
    /// waits behind any message already showing.
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
            showing: runToastPending ? toastRuns : nil
        )
        announcedRuns = update.seen
        // Built from the list as it is now. Nil when none of the runs is a
        // row any more (a match deleted while its run was finishing).
        guard let ids = update.toastRuns,
              let message = FinishedRunMessageKt.finishedRunMessage(ids: ids, list: listModel.matchList)
        else { return }
        let item = message.item
        let runToast = ToastMessage(
            text: message.text,
            actionLabel: item == nil ? nil : MatchPageCopy.shared.VIEW,
            action: item.map { item in { openAfterRun(item) } }
        )
        if let old = runToastId, toast?.id == old {
            toast = runToast
        } else if let old = runToastId, let at = toastQueue.firstIndex(where: { $0.id == old }) {
            toastQueue[at] = runToast
        } else {
            toastQueue.append(runToast)
        }
        toastRuns = ids
        runToastId = runToast.id
        deliverToast()
    }

    /// A finished run's View: its match, on what the run made.
    ///
    /// The row as it is at the tap, not as it was when the run landed: the
    /// announcement can come before the list has caught up (a cloud run's
    /// stored track lands after its finish), and the frozen row would then
    /// have no Analysis to open on. Falls back to the frozen row if the match
    /// has left the list; the page says it is gone. Mirrors androidApp's
    /// RootAnnouncementsHost.
    private func openAfterRun(_ landed: MatchListItem) {
        let item = listModel.matchList.matchPageItem(
            scoreLogId: landed.scoreLogId, videoId: landed.videoId, entryId: landed.entryId
        ) ?? landed
        path.append(.match(MatchRoute(
            scoreLogId: item.scoreLogId, videoId: item.videoId, entryId: item.entryId,
            section: MatchPageSectionKt.sectionAfterRun(item: item)
        )))
    }

    /// The failed-run dialog's Retry: resume the run, or mark the court first
    /// when the run needs it. Read from the store, not the list's cache: the
    /// decision turns on the entry's saved court points and failed step as
    /// they are now. The shared rule, as the list's own Analyze uses.
    private func analyzeEntry(_ entryId: String) {
        guard let entry = rally.localVideos.get(id: entryId) else { return }
        switch analyseAction(for: entry) {
        case .resume(let id): analyze.retry(entryId: id)
        case .markCourt(let id): path.append(.courtMarking(CourtMarkingRoute(entryId: id)))
        }
    }

    /// Where the chrome indicator sends a coach (Ruling 13): All matches, the
    /// list the run it points at is a row on, with nothing above it. One
    /// reassignment of the path, whatever was on it: see `HomePath`.
    ///
    /// Pushes only, not everything `nothingAbove` checks: sheets need no
    /// clearing, because `BackgroundWorkAction` never appears inside a sheet,
    /// so no tap on it can come from under one.
    private func goToAllMatches() {
        path = HomePath.toAllMatches(path)
    }
}

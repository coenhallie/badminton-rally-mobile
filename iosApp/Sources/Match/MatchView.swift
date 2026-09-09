import SwiftUI
import Shared

/// A route to one match, however it was made: a video id, a score log id, or
/// both once a scored match has acquired a video. Replaces both `String` (a
/// video's id) and `ScoreMatchRoute` as the value pushed for a match row.
struct MatchRoute: Hashable {
    let scoreLogId: String?
    let videoId: String?
    /// The intent carried from the board's "Add the video?" prompt - "Import" or
    /// "Record" chosen there, or nil (either "Not now", or a route pushed by
    /// something other than that prompt, e.g. a plain row tap). Not part of a
    /// route's identity beyond a single read by the match page itself - nothing
    /// pops or re-pushes a `MatchRoute` by reconstructing one, so two instances
    /// for the same match carrying different `attach` values is fine.
    var attach: AttachIntent? = nil
}

/// Which half of a match page is on screen. Only meaningful when the match has
/// both. Port of Android's private `Facet` enum in `MatchScreen.kt`.
private enum Facet {
    case points
    case rallies
}

/// A confirmed answer to the video confirm, distinct from "not answered yet":
/// `intent` is nil for a plain removal, which is not the same as a Cancel.
private struct ConfirmedVideoRemoval {
    let intent: AttachIntent?
}

/// `navigationDestination(item:)` needs an Identifiable, and the summary sheet's
/// "most labelled" row pushes a clip programmatically rather than through a
/// NavigationLink.
private struct ClipRoute: Identifiable, Hashable {
    let id: String
}

/// The two sheets this page can show, as one `.sheet(item:)` rather than two
/// stacked `.sheet(isPresented:)` modifiers - stacking two on the same view is a
/// known SwiftUI footgun where presentation can silently fail for one of them.
/// No associated data: the content closure reads `summary`/`effectiveVideoId`
/// from the enclosing view's state directly, so a summary that updates while its
/// sheet is open is still reflected live, the same as before this type existed.
private enum MatchSheet: Identifiable, Hashable {
    case share
    case summary
    var id: Self { self }
}

/// One match, however it was made: a video-first or shared match shows only the
/// rallies facet, a scored match with no video shows only the points facet, and a
/// match with both gets a selector between them. Replaces `MatchClipsView` and
/// `ScoreMatchView`.
struct MatchView: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    /// Routed through only so the court marking this page can push offers the
    /// same two targets it offers everywhere else.
    let localAnalysis: LocalAnalysisRunner?
    let route: MatchRoute

    @State private var matchModel: MatchModel
    // This page owns the picker, the same way `ClipListView` owns its own: "add
    // a video to this match" needs exactly one implementation, whether it starts
    // from this page's own "Add video" menu or from the board's finish prompt.
    // A separate instance from ClipListView's - the two never race, since the
    // match this page attaches to is the one thing ClipListView's own intake
    // cannot be mid-picking on when this page is on screen.
    @State private var intake: LocalVideoIntake
    @State private var showImporter = false
    @State private var showRecorder = false
    @State private var pendingAttachTarget: MatchTarget? = nil
    @State private var navigationTarget: CourtMarkingRoute? = nil
    // This page is the one and only pusher of its own board: Score/Resume
    // pushes through this state, not a bare `NavigationLink(value:)` resolved
    // by an ambient destination elsewhere. That is what lets the board finish
    // by popping back to this exact instance and handing it the chosen intent
    // directly, rather than a fresh match page being pushed on top of this one -
    // see `matchPageAlreadyOpen` on `ScoringView`.
    @State private var scoringTarget: ScoringRoute? = nil
    // `route.attach` is only ever meant to be acted on once per push of this
    // page - a fresh `MatchRoute` each time the prompt fires, per `MatchRoute`'s
    // own doc comment - so this flag (scoped to this view instance) is enough;
    // `.onAppear` re-firing on a later return to this same instance must not
    // replay it.
    @State private var attachHandled = false
    // Fetched here rather than handed down: the list navigates by video id alone,
    // and a soft-failing read is cheaper than threading a summary through the route.
    @State private var allClips: [RallyClip] = []
    @State private var sort: ClipSort = .rallyOrder
    @State private var metadata: MatchMetadata? = nil
    @State private var summary: MatchLabelSummary? = nil
    @State private var isLoadingSummary = false
    // A superseded fetch must not write its result: the awaited Kotlin bridge does
    // not observe Swift task cancellation, so a replaced .task keeps running to
    // completion. The generation it captured tells it to drop what it got.
    @State private var summaryGeneration = 0
    @State private var clipRoute: ClipRoute? = nil
    // Set while the summary sheet is closing; the push happens in the sheet's
    // onDismiss rather than inline with dismiss(), because a navigationDestination
    // on the view that is presenting a sheet can drop a push made mid-dismissal.
    @State private var pendingTopRallyClipId: String? = nil
    @State private var activeSheet: MatchSheet? = nil
    // Which of the two video gestures is awaiting its confirm, or nil. Both are
    // irreversible, and both go through the same alert.
    @State private var videoAction: MatchVideoAction? = nil
    // How the confirm above was answered, held until the alert has actually gone -
    // see `confirmedVideoRemoval` and the onChange that acts on it. nil means it
    // was cancelled, which is why the answer cannot just be the intent.
    @State private var confirmedVideoRemoval: ConfirmedVideoRemoval? = nil
    // Held for the life of this view, not re-keyed on hasPoints/hasRallies: the
    // clip list briefly empties during a refresh, and re-keying on that would
    // silently snap a reader on the rallies facet back to Points mid-read.
    @State private var chosenFacet: Facet

    init(
        rally: RallyApp,
        analyze: AnalyzeCoordinator,
        localAnalysis: LocalAnalysisRunner?,
        route: MatchRoute
    ) {
        self.rally = rally
        self.analyze = analyze
        self.localAnalysis = localAnalysis
        self.route = route
        let model = MatchModel(rally: rally, analyze: analyze, scoreLogId: route.scoreLogId)
        _matchModel = State(initialValue: model)
        _chosenFacet = State(initialValue: model.log != nil ? .points : .rallies)
        _intake = State(initialValue: LocalVideoIntake(rally: rally))
    }

    // The score log is authoritative: a match bound after this page was opened
    // must start showing its rallies without a re-entry.
    private var effectiveVideoId: String? { route.videoId ?? matchModel.log?.videoId }

    private var clipsForMatch: [RallyClip] {
        sort.sorted(allClips.filter { $0.videoId == effectiveVideoId })
    }

    // Shown only when the match has both. A selector over one facet is a control
    // that does nothing, and a match that has only rallies must look exactly like
    // it did before this page existed.
    private var hasPoints: Bool { matchModel.log != nil }
    private var hasRallies: Bool { !clipsForMatch.isEmpty }

    // What actually renders: the user's choice when it is still available, else
    // whichever facet the match currently has.
    private var facet: Facet {
        if chosenFacet == .points && hasPoints { return .points }
        if chosenFacet == .rallies && hasRallies { return .rallies }
        if hasPoints { return .points }
        return .rallies
    }

    // Reuses `MatchGrouping.matches` rather than re-deriving ownership from a clip
    // lookup here: that function is what `ClipListView`'s own share button already
    // trusts, and two independent answers to "is this mine" that can disagree is a
    // defect regardless of which one happens to be right at a given moment.
    private var isOwned: Bool {
        guard let effectiveVideoId else { return false }
        let (owned, _) = MatchGrouping.matches(
            from: allClips.map(ClipInfo.init),
            currentUserId: rally.auth.currentUserId(),
            sharerByVideoId: [:],
            metadataByVideoId: [:]
        )
        return owned.contains { $0.videoId == effectiveVideoId }
    }

    private var matchName: String? {
        // videos.title is authoritative; the clip-stamped copy keeps the name on
        // screen when the RPC is unreachable.
        metadata?.title ?? matchTitle(of: clipsForMatch.map(ClipInfo.init))
    }

    // Precedence matches Android's `MatchScreen`: a resolved video match owns its
    // title first, then a score log's title, then the bare "RALLIES" fallback -
    // never the other order. A match the coach named on the phone and then
    // attached a video to must keep showing that name while the video's clips and
    // metadata are still loading, not flash "RALLIES" in between.
    private var title: String {
        if let name = matchName { return name.uppercased() }
        if let latest = clipsForMatch.map({ $0.createdAt.toEpochMilliseconds() }).max() {
            return "MATCH · \(formatMatchDate(millis: latest).uppercased())"
        }
        // Score-only, or a video match whose clips/metadata have not loaded yet:
        // same title as the screen this page replaced.
        if let log = matchModel.log { return log.title }
        return "RALLIES"
    }

    // The score log this page was opened for is gone - deleted elsewhere while it
    // was open, or never synced to this device. A video-first match with no log at
    // all is not this state: `hasPoints` is false for it and it falls straight
    // through to the rallies facet, same as before this page existed.
    private var isMissing: Bool {
        route.scoreLogId != nil && matchModel.log == nil && effectiveVideoId == nil
    }

    /// `supersede` is for the triggers that must never be swallowed: a changed clip
    /// set and an explicit pull to refresh. Only `.onAppear` defers to a fetch that
    /// is already running, matching `MatchClipsView`'s original contract.
    private func refreshSummary(supersede: Bool = false) async {
        let target = clipsForMatch
        guard !target.isEmpty else {
            summary = nil
            return
        }
        guard supersede || !isLoadingSummary else { return }

        summaryGeneration += 1
        let generation = summaryGeneration
        isLoadingSummary = true
        defer { if generation == summaryGeneration { isLoadingSummary = false } }

        // Soft failure: keep whatever is on screen and say nothing.
        guard let rows = try? await SwiftInteropKt.listForClipsOrNull(
            rally.annotations, clipIds: target.map(\.id)
        ) else { return }
        guard generation == summaryGeneration else { return }
        // Built from the clips this fetch was made for, never from a newer list.
        summary = MatchLabelSummaryKt.buildMatchLabelSummary(clips: target, annotations: rows)
    }

    var body: some View {
        VStack(spacing: 0) {
            if let intakeError = intake.error {
                ErrorBanner(message: intakeError)
            }
            // Its own banner line, not the intake's: removing this match's video
            // is the one thing this page does that can fail on its own.
            if let removeError = matchModel.error {
                ErrorBanner(message: removeError)
            }
            Group {
                if isMissing {
                    VStack {
                        Text("This match is no longer on this phone.")
                            .foregroundStyle(Shuttl.textSecondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    VStack(spacing: 0) {
                        // Same status, same actions as the match list's own row -
                        // see AttachStatusBanner. Without this the page was blind
                        // about an attach in progress while simultaneously still
                        // offering "Add video", because canAddVideo used to close
                        // only on a videoId.
                        if let attach = matchModel.attach {
                            AttachStatusBanner(
                                attach: attach,
                                onMarkCourt: { attachMarkCourt() },
                                onRetry: { attachRetry() }
                            )
                        }
                        if hasPoints && hasRallies {
                            // Reads the derived facet, writes the chosen one: the
                            // control shows what is actually on screen, but a later
                            // fallback (rallies emptying mid-refresh) must not overwrite
                            // what the user picked - see `chosenFacet`'s own comment.
                            ShuttlPillTabs(
                                labels: ["Points", "Rallies"],
                                selectedIndex: facet == .points ? 0 : 1,
                                onSelect: { chosenFacet = $0 == 0 ? .points : .rallies },
                                accessibilityLabel: "Facet"
                            )
                            .padding(.horizontal)
                            .padding(.vertical, 8)
                        }
                        listContent
                    }
                }
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbarContent }
        // A summary that goes away while its sheet is open must close the sheet,
        // not leave it standing over data that no longer exists.
        .onChange(of: summary?.isEmpty) { _, isEmpty in
            if isEmpty != false, activeSheet == .summary { activeSheet = nil }
        }
        .sheet(item: $activeSheet, onDismiss: {
            guard let clipId = pendingTopRallyClipId else { return }
            pendingTopRallyClipId = nil
            clipRoute = ClipRoute(id: clipId)
        }) { sheet in
            switch sheet {
            case .share:
                if let videoId = effectiveVideoId {
                    ShareSheetView(rally: rally, videoId: videoId)
                }
            case .summary:
                if let summary {
                    MatchSummarySheet(
                        summary: summary,
                        topRallyName: summary.topRally.map {
                            topRallyName(
                                clipId: $0.clipId, rallyIndex: $0.rallyIndex,
                                clips: clipsForMatch.map(ClipInfo.init), matchTitle: matchName
                            )
                        },
                        onTopRally: {
                            // Parity with Android, which looks the clip up and
                            // no-ops when it is gone rather than pushing a detail
                            // view for a clip that no longer exists.
                            if let top = summary.topRally,
                               clipsForMatch.contains(where: { $0.id == top.clipId }) {
                                pendingTopRallyClipId = top.clipId
                            }
                        }
                    )
                    .presentationDetents([.medium, .large])
                }
            }
        }
        .navigationDestination(item: $clipRoute) { route in
            ClipDetailView(rally: rally, clipId: route.id)
        }
        .navigationDestination(item: $navigationTarget) { route in
            CourtMarkingView(
                rally: rally, analyze: analyze,
                localAnalysis: localAnalysis, entryId: route.entryId
            )
        }
        .navigationDestination(item: $scoringTarget) { route in
            ScoringView(
                rally: rally,
                scoreLogId: route.scoreLogId,
                matchPageAlreadyOpen: true,
                onFinished: { intent in
                    // Nothing pushed: this page is already on the stack right
                    // where the board's own pop leaves it. `intent` is nil for
                    // Done or "Not now" - there is nothing further to do then,
                    // `matchModel`'s own subscription already reflects whatever
                    // the board changed.
                    if let intent { attachVideo(intent) }
                }
            )
        }
        // `presenting:` rather than a bool and a separate payload: the alert's
        // title, body and buttons all depend on which gesture raised it, and the
        // four must not be able to disagree about that.
        .alert(
            videoPrompt?.title ?? "",
            isPresented: Binding(get: { videoAction != nil }, set: { if !$0 { videoAction = nil } }),
            presenting: videoAction
        ) { action in
            matchVideoAlertActions(action)
        } message: { _ in
            Text(videoPrompt?.body ?? "")
        }
        // The removal, and for a change the picker after it, run from here rather
        // than from the alert's own button closures: a sheet raised while an alert
        // is still tearing down is silently dropped, the same class of failure
        // `MatchSheet` exists for, and `showImporter` is exactly that sheet.
        // `videoAction` going nil is the alert's dismissal, and a Cancel leaves
        // `confirmedVideoRemoval` nil, so this fires only on a real answer.
        .onChange(of: videoAction) { _, current in
            guard current == nil, let confirmed = confirmedVideoRemoval else { return }
            confirmedVideoRemoval = nil
            Task { await removeVideo(then: confirmed.intent) }
        }
        .sheet(isPresented: $showImporter) {
            VideoPicker(
                onPicked: { tempURL, suggestedName in
                    let target = pendingAttachTarget
                    Task { await intake.add(tempURL: tempURL, suggestedName: suggestedName, isRecording: false, forMatch: target) }
                },
                onFailed: { intake.error = "Couldn't import the video. Please try again." }
            )
        }
        .fullScreenCover(isPresented: $showRecorder) {
            CameraRecorder { tempURL in
                let target = pendingAttachTarget
                Task { await intake.add(tempURL: tempURL, suggestedName: nil, isRecording: true, forMatch: target) }
            }
            .ignoresSafeArea()
        }
        .onChange(of: intake.lastAddedId) { _, id in
            guard let id else { return }
            // Consumed unconditionally, before the lookup can fail - see
            // ClipListView's identical guard for why.
            intake.lastAddedId = nil
            // The coach already said he wants this video analysed; sending him
            // straight to court marking rather than back to this page first is
            // the same shortcut `AuthGate.kt`'s `onAdded` takes for a match-
            // attached pick, and the whole reason Step 2 skips the details sheet.
            navigationTarget = CourtMarkingRoute(entryId: id)
        }
        // The picker is plumbed to this page and nowhere else, so "attach a video
        // to this match" has one implementation and two entry points: the "Add
        // video" menu below, and the prompt the board raises when a match
        // finishes (`route.attach`, read once per push of this page).
        .onAppear {
            guard !attachHandled, let attach = route.attach else { return }
            attachHandled = true
            attachVideo(attach)
        }
        .task { await matchModel.start() }
        // Soft failure: no metadata just means the date headline, as before. Keyed
        // on the effective video id, not a bare `.task`: a score-only match that
        // acquires a video after this page opens must pick up its metadata without
        // a re-entry, the same reason `effectiveVideoId` itself is derived.
        .task(id: effectiveVideoId) {
            guard let effectiveVideoId else {
                metadata = nil
                return
            }
            let rows = try? await SwiftInteropKt.listMatchMetadataOrNull(rally.videos)
            metadata = rows?.first { $0.videoId == effectiveVideoId }
        }
        // A Set, not the sorted array: changing the sort order reorders
        // `clipsForMatch` without changing which clips are in it, and that must
        // not look like a changed clip set to this trigger.
        .task(id: Set(clipsForMatch.map(\.id))) { await refreshSummary(supersede: true) }
        // Pushing ClipDetailView does not remove this view, so its .task is not
        // restarted on the way back, and adding a note does not change clipIds.
        // Without this the strip would go stale exactly where it matters most.
        .onAppear { Task { await refreshSummary() } }
        .task {
            for await latest in rally.clips.observeClips() {
                allClips = latest
            }
        }
    }

    // A pull gesture that spins and refreshes nothing is worse than no gesture: a
    // score-only match has no video, so there is nothing here for a refresh to
    // touch. Only a video-backed match keeps the old `MatchClipsView`'s
    // pull-to-refresh, including when both facets exist and Points is selected.
    @ViewBuilder
    private var listContent: some View {
        if effectiveVideoId != nil {
            listBody.refreshable {
                try? await rally.clips.refresh()
                await refreshSummary(supersede: true)
            }
        } else {
            listBody
        }
    }

    /// Starts a pick for this match, from either entry point (the "Add video"
    /// menu or the board's finish prompt). Mirrors `AuthGate.kt`'s `onAddVideo`.
    private func attachVideo(_ intent: AttachIntent) {
        guard let log = matchModel.log else { return }
        // A match acquires a video only once it is closed: attaching to a log
        // still marked live would leave a bound match advertising "Resume
        // scoring" for the whole upload and clipping window.
        if log.status == .live {
            rally.scoreLogs.finish(id: log.id)
            matchModel.reload()
        }
        pendingAttachTarget = MatchTarget(scoreLogId: log.id, title: log.title)
        switch intent {
        case .importVideo:
            showImporter = true
        case .record:
            // Same guard as ClipListView's own record entry point: the
            // simulator (and any device with no camera) has nothing for
            // `showRecorder` to present. Checked here rather than at each call
            // site, so both this page's own menu and the board's finish prompt
            // (which reaches this through `route.attach`) get it for free.
            if CameraRecorder.isAvailable {
                showRecorder = true
            } else {
                intake.error = "Camera is not available on this device."
            }
        }
    }

    /// Same lookup and action as the merged row's own "Mark court" -
    /// `ClipListView.scoreRow`'s `.courtNotMarked` case - so the match page
    /// offers exactly what the list row offers for the same match.
    private func attachMarkCourt() {
        guard let entry = rally.localVideos.entries.value.first(where: { $0.scoreLogId == matchModel.scoreLogId })
        else { return }
        navigationTarget = CourtMarkingRoute(entryId: entry.id)
    }

    /// Same lookup and action as the merged row's own "Retry" -
    /// `ClipListView.scoreRow`'s `.failed` case.
    private func attachRetry() {
        guard let entry = rally.localVideos.entries.value.first(where: { $0.scoreLogId == matchModel.scoreLogId })
        else { return }
        analyze.retry(entryId: entry.id)
    }

    /// What the confirm says, built in shared
    /// (`MatchVideoRemovalKt.matchVideoPrompt`) rather than here, for the same
    /// reason `AttachStatus.text` is: two platforms writing the same sentence are
    /// two chances to write it differently. It has to say the points survive,
    /// which is the opposite of the match list's bound-match confirm, so that
    /// wording could not be reused.
    private var videoPrompt: MatchVideoPrompt? {
        videoAction.map {
            MatchVideoRemovalKt.matchVideoPrompt(action: $0, hasServerVideo: matchModel.hasServerVideo)
        }
    }

    /// The source for a change is chosen on the confirm itself rather than in a
    /// second alert after the video is already gone. Mirrors Android's
    /// `MatchVideoDialog`.
    @ViewBuilder
    private func matchVideoAlertActions(_ action: MatchVideoAction) -> some View {
        switch action {
        case .change:
            Button("Import video") { confirmedVideoRemoval = ConfirmedVideoRemoval(intent: .importVideo) }
            Button("Record") { confirmedVideoRemoval = ConfirmedVideoRemoval(intent: .record) }
            Button("Cancel", role: .cancel) {}
        case .remove:
            Button("Remove", role: .destructive) { confirmedVideoRemoval = ConfirmedVideoRemoval(intent: nil) }
            Button("Cancel", role: .cancel) {}
        }
    }

    /// Runs the removal and, for a change, hands straight over to the picker this
    /// page already owns: once removal lands the match has no video in either
    /// sense, so nothing about the attach path changes. Mirrors Android's
    /// `MatchViewModel.removeVideo(onRemoved:)`.
    private func removeVideo(then intent: AttachIntent?) async {
        guard await matchModel.removeVideo() else { return }
        if let intent { attachVideo(intent) }
    }

    private var listBody: some View {
        List {
            if facet == .points, let log = matchModel.log {
                PointsFacet(
                    log: log, card: matchModel.card, tally: matchModel.tally,
                    points: matchModel.match?.points ?? [],
                    onScore: { scoringTarget = ScoringRoute(scoreLogId: log.id) }
                )
            } else {
                RalliesFacet(
                    rally: rally,
                    clips: clipsForMatch,
                    summary: summary,
                    matchTitle: matchName,
                    description: metadata?.description_,
                    onSummaryClick: { activeSheet = .summary }
                )
            }
        }
        .listStyle(.plain)
    }

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        // The sort order only means something while rallies are on screen.
        if facet == .rallies {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Picker("Sort", selection: $sort) {
                        Text("Rally order").tag(ClipSort.rallyOrder)
                        Text("Most notes").tag(ClipSort.mostNotes)
                    }
                } label: {
                    Image(systemName: "arrow.up.arrow.down")
                }
            }
        }
        if isOwned, effectiveVideoId != nil {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    activeSheet = .share
                } label: {
                    Image(systemName: "square.and.arrow.up")
                }
                .accessibilityLabel("Share match")
            }
        }
        // A finished match with no video yet - the one condition
        // `MatchModel.canAddVideo` is true for.
        if matchModel.canAddVideo {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button("Import video") { attachVideo(.importVideo) }
                    Button("Record video") { attachVideo(.record) }
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("Add video")
            }
        }
        // One overflow, matching Android's own on this page, rather than a bare
        // glyph per action: with sort, share and "Add video" already in the bar,
        // three more discrete items would crowd it, and two of them would read as
        // near-identical icons under VoiceOver.
        if let log = matchModel.log {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    ShareLink(item: ScoreTagSummaryKt.exportMatchText(log: log)) {
                        Label("Export as text", systemImage: "doc.text")
                    }
                    // Offered whenever this match has a video and nothing is in
                    // flight for it - not only when the analysis disappointed. A
                    // video that found no rallies is the likeliest reason to want
                    // another one, but gating on that would leave the "Finishing
                    // up…" dead end with no action on it at all. See the
                    // 2026-08-29 design.
                    if matchModel.canRemoveVideo {
                        Button("Change video") { videoAction = .change }
                        Button("Remove video", role: .destructive) { videoAction = .remove }
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .accessibilityLabel("Match options")
            }
        }
    }
}

/// What the match list's row says about this match's video, repeated on the
/// match page itself: §4.9 of the 2026-08-28 design requires the page to say so
/// and offer the same actions, not leave the coach reading a page that says
/// nothing while "Add video" is also gone with no explanation. Same text, same
/// three actions (Mark court / Retry / a spinner) as `ClipListView.scoreRow`.
private struct AttachStatusBanner: View {
    let attach: AttachStatus
    let onMarkCourt: () -> Void
    let onRetry: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Text(attach.text)
                .shuttlType(ShuttlType.titleMedium)
                .foregroundStyle(attach.kind == .failed ? Shuttl.error : Shuttl.textSecondary)
                .lineLimit(2)
            Spacer()
            switch attach.kind {
            case .courtNotMarked:
                Button(action: onMarkCourt) {
                    Text("Mark court")
                        .shuttlType(ShuttlType.labelMedium)
                        .foregroundStyle(Shuttl.onAccent)
                        .lineLimit(1)
                        .fixedSize(horizontal: true, vertical: false)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Shuttl.accent)
                        .clipShape(Capsule())
                }
                .buttonStyle(.borderless)
            case .failed:
                Button(action: onRetry) {
                    Text("Retry")
                        .shuttlType(ShuttlType.labelMedium)
                        .foregroundStyle(Shuttl.onAccent)
                        .lineLimit(1)
                        .fixedSize(horizontal: true, vertical: false)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Shuttl.accent)
                        .clipShape(Capsule())
                }
                .buttonStyle(.borderless)
            case .uploading, .clipping, .finishingUp:
                ProgressView()
                    .controlSize(.small)
                    .frame(width: 24, height: 24)
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 12)
    }
}

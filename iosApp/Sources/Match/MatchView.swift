import SwiftUI
import Shared

/// A route to one match, however it was made: a video id, a score log id, or
/// both once a scored match has acquired a video. Replaces both `String` (a
/// video's id) and `ScoreMatchRoute` as the value pushed for a match row.
struct MatchRoute: Hashable {
    let scoreLogId: String?
    let videoId: String?
    /// A video only on this phone has no cloud id and no score log: this is how
    /// the page finds it.
    var entryId: String? = nil
    /// The intent carried from the board's "Add the video?" prompt - "Import" or
    /// "Record" chosen there, or nil (either "Not now", or a route pushed by
    /// something other than that prompt, e.g. a plain row tap). Not part of a
    /// route's identity beyond a single read by the match page itself - nothing
    /// pops or re-pushes a `MatchRoute` by reconstructing one, so two instances
    /// for the same match carrying different `attach` values is fine.
    var attach: AttachIntent? = nil
    /// The section to open on, from a finished run's View; nil opens the
    /// default.
    var section: MatchPageSection? = nil
}

/// The match page's vertical rhythm, androidApp's `MatchSectionGap` and
/// `MatchRowInset`. The side margin is `ShuttlGutter.page`, the same 24pt for
/// every section.
enum MatchPageLayout {
    /// Between the section row (or the bar, on a page with one section) and
    /// the first thing the section draws, the same for every section: each
    /// draws from its own top edge. 16 is what the Analysis section already
    /// had between its two pill rows.
    static let sectionGap: CGFloat = 16
    /// The top inset of a list section's first row (a rally row's, the Points
    /// header's, the label strip's), counted into `sectionGap` so a list's
    /// first line lands where the video card and the panel row do.
    static let rowInset: CGFloat = 14
}

/// A confirmed answer to the video confirm, distinct from "not answered yet":
/// `intent` is nil for a plain removal, which is not the same as a Cancel.
private struct ConfirmedVideoRemoval {
    let intent: AttachIntent?
}

/// `navigationDestination(item:)` needs an Identifiable. Every push of a rally
/// goes through it: a rally row's tap in the Rallies section, and the summary
/// sheet's "most labelled" row.
private struct ClipRoute: Identifiable, Hashable {
    let id: String
}

/// The sheets this page can show, as one `.sheet(item:)` rather than two
/// stacked `.sheet(isPresented:)` modifiers - stacking two on the same view is a
/// known SwiftUI footgun where presentation can silently fail for one of them.
/// No associated data: the content closure reads `summary`/`effectiveVideoId`
/// from the enclosing view's state directly, so a summary that updates while its
/// sheet is open is still reflected live, the same as before this type existed.
private enum MatchSheet: Identifiable, Hashable {
    case share
    case summary
    case details
    var id: Self { self }
}

/// One match, however it was made, and every way into it: a row's tap on the
/// list opens this page whatever the match has. It shows the match's sections
/// (Points, Match, Rallies, Analysis), decided by the shared `matchPageSections`
/// rule from the list's own row, with a toggle row between them when there is
/// more than one. Replaces `MatchClipsView`, `ScoreMatchView` and the separate
/// screens a video on this phone had for its player, its device clips and its
/// heatmap. Mirrors androidApp's `MatchScreen`.
struct MatchView: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    /// Routed through only so the court marking this page can push offers the
    /// same two targets it offers everywhere else.
    let localAnalysis: LocalAnalysisRunner?
    /// The app's one list model (Home's): the page finds its row in it.
    let listModel: ClipListModel
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
    // The repository's cache, followed rather than fetched once: a rename made
    // from this page's own editor lands there and must redraw the title and the
    // details line without a re-entry.
    @State private var metadataByVideoId: [String: MatchMetadata] = [:]
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
    /// Held for the life of this page and never cleared: a section can go
    /// while on screen, and `shownSection` falls back without forgetting it.
    /// Also never re-keyed on the sections themselves: the clip list briefly
    /// empties during a refresh, and re-keying on that would snap a reader on
    /// Rallies back to Points mid-read.
    @State private var chosenSection: MatchPageSection?

    init(
        rally: RallyApp,
        analyze: AnalyzeCoordinator,
        localAnalysis: LocalAnalysisRunner?,
        listModel: ClipListModel,
        route: MatchRoute
    ) {
        self.rally = rally
        self.analyze = analyze
        self.localAnalysis = localAnalysis
        self.listModel = listModel
        self.route = route
        _matchModel = State(initialValue: MatchModel(rally: rally, scoreLogId: route.scoreLogId))
        _chosenSection = State(initialValue: route.section)
        _intake = State(initialValue: LocalVideoIntake(rally: rally))
    }

    /// The row this page is for, from the list the Matches screen draws, and
    /// its sections by the one shared rule: the chips on that row are the
    /// toggles here.
    private var item: MatchListItem? {
        listModel.matchList.matchPageItem(
            scoreLogId: route.scoreLogId,
            videoId: route.videoId ?? matchModel.log?.videoId,
            entryId: route.entryId
        )
    }

    private var sections: [MatchPageSection] {
        item.map { MatchPageSectionKt.matchPageSections(item: $0) } ?? []
    }

    /// What the page draws: the coach's choice while it still exists, else the
    /// default. Read everywhere the page decides what to show; `chosenSection`
    /// is only ever written.
    private var section: MatchPageSection? {
        MatchPageSectionKt.shownSection(chosen: chosenSection, sections: sections)
    }

    /// Under the player only for a video with no score log: a scored match's
    /// video asks through `StatusBanner` already, and two buttons for
    /// one question is the kind of drift the shared rule exists to stop.
    private var analyzeLabel: String? {
        guard let item, item.scoreLogId == nil, item.action == .analyze || item.action == .retry else { return nil }
        return MatchListCopy.shared.actionLabel(action: item.action)
    }

    // The list's row first, then the score log: a match bound after this page
    // was opened must start showing its rallies without a re-entry.
    private var effectiveVideoId: String? { item?.videoId ?? route.videoId ?? matchModel.log?.videoId }

    private var clipsForMatch: [RallyClip] {
        sort.sorted(allClips.filter { $0.videoId == effectiveVideoId })
    }

    private var metadata: MatchMetadata? {
        effectiveVideoId.flatMap { metadataByVideoId[$0] }
    }

    // This match's video, when it is the coach's own, as `MatchGrouping.matches`
    // folds its clips: the clips' owner decides (every clip of a video has the
    // same one), the same fact the match list's shared builder reads for
    // its row's `isOwned` and so for its share button. `MatchGrouping` stays
    // for this page's `MatchSummary`; the list no longer builds one.
    private var ownedVideo: MatchSummary? {
        guard let effectiveVideoId else { return nil }
        let (owned, _) = MatchGrouping.matches(
            from: allClips.map(ClipInfo.init),
            currentUserId: rally.auth.currentUserId(),
            sharerByVideoId: [:],
            metadataByVideoId: metadataByVideoId
        )
        return owned.first { $0.videoId == effectiveVideoId }
    }

    private var isOwned: Bool { ownedVideo != nil }

    // The score log's details when there is one, the video's own otherwise: a
    // scored match that was later filmed keeps the log as its record.
    private var detailsLine: String? {
        MatchDetailsKt.matchDetailsLine(
            playersLine: matchModel.log?.playersLine() ?? ownedVideo?.playersLine,
            format: matchModel.log?.format ?? ownedVideo?.format
        )
    }

    private var matchName: String? {
        // videos.title is authoritative; the clip-stamped copy keeps the name on
        // screen when the RPC is unreachable.
        metadata?.title ?? matchTitle(of: clipsForMatch.map(ClipInfo.init))
    }

    // Precedence matches Android's `MatchScreen`. The list's own name for this
    // match first, so the page and the row one tap back cannot read as two
    // matches, set as the row sets it. Until the row arrives, a match with a
    // video owns its title (the builder's own order: the name, else the date),
    // then a score log's. A match the coach named on the phone and then attached
    // a video to must keep showing that name while the video's clips and
    // metadata are still loading. Nothing at all for a match that is gone: the
    // body says so, and a title would name a match that is not there.
    private var title: String {
        if isMissing { return "" }
        if let item { return item.title }
        if let name = matchName { return name }
        if let latest = clipsForMatch.map({ $0.createdAt.toEpochMilliseconds() }).max() {
            return "Match · \(formatMatchDate(millis: latest))"
        }
        if let log = matchModel.log { return log.title }
        return ""
    }

    /// Whether the page's own reads still find the match its route names. They
    /// need no network and are current on the first frame, where the list's
    /// rows can trail them: the list's loops stop while a page is pushed over
    /// it, so a match made under a push (New match, the board, then this page)
    /// is not in `matchList` for the moment this page's own `start()` takes to
    /// deliver. Without this the page would say "no longer on this phone", for
    /// that moment, about a match it had just been handed.
    private var foundDirectly: Bool {
        if route.scoreLogId != nil { return matchModel.log != nil }
        return route.entryId.map { rally.localVideos.get(id: $0) != nil } ?? false
    }

    /// Gone (deleted elsewhere, a share left, or the file removed from a match
    /// that was only on this phone), rather than not listed yet. The list is
    /// Home's, so once it has finished one refresh (`refreshGeneration` counts
    /// completed ones, failed included) a row it lacks is not coming: the list
    /// holds everything it knows about. Before that, only the lookups that need
    /// no network can tell. Mirrors androidApp's `gone`.
    private var isMissing: Bool {
        guard item == nil, !foundDirectly else { return false }
        return listModel.refreshGeneration > 0
            || (route.scoreLogId != nil && effectiveVideoId == nil)
            || (route.scoreLogId == nil && route.videoId == nil && route.entryId != nil)
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
                        Text(MatchPageCopy.shared.MISSING)
                            .shuttlType(ShuttlType.bodyLarge)
                            .foregroundStyle(Shuttl.textSecondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, ShuttlGutter.page)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    VStack(spacing: 0) {
                        // The match list's own row, status and action both - see
                        // StatusBanner. Without this the page was blind about an
                        // attach in progress while simultaneously still offering
                        // "Add video", because canAddVideo used to close only on
                        // a videoId.
                        if let item, MatchPageSectionKt.showsStatusBanner(item: item) {
                            StatusBanner(item: item) { bannerAction(item) }
                        }
                        // A row over one section is a control that does nothing,
                        // and a match with one section must look like a page, not
                        // a picker. Reads the shown section, writes the chosen
                        // one: the row shows what is on screen, but a fallback
                        // (a section going) must not overwrite what the coach
                        // picked - see `chosenSection`.
                        if sections.count > 1, let section {
                            ShuttlPillTabs(
                                labels: sections.map(\.label),
                                selectedIndex: sections.firstIndex(of: section) ?? 0,
                                onSelect: { chosenSection = sections[$0] },
                                accessibilityLabel: MatchPageCopy.shared.SECTIONS,
                                // The secondary rows in the sections below
                                // (Analysis's panels, Near and Far) draw no larger
                                // than this row. See ShuttlPillTabs.
                                setsCeiling: true
                            )
                            .padding(.horizontal, ShuttlGutter.page)
                            .padding(.top, 8)
                        }
                        sectionContent
                            // The gap under the section row (or the bar, on a
                            // page with one section), the same for every section.
                            // Points and Rallies are lists whose first row holds
                            // its first line `rowInset` off its own top, so they
                            // get the rest of it. Two first rows sit a little
                            // lower, as on Android: the rally sort control, whose
                            // label is centred in its touch target, and "No
                            // rallies yet", which is centred in the section rather
                            // than hung from its top. Analysis takes all of it
                            // inside its scroll view (see AnalysisSection).
                            .padding(.top, sectionTopPadding)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                    }
                    // Collects the section row's label size for the secondary
                    // pill rows inside `sectionContent`.
                    .pillLabelCeilingHost()
                }
            }
        }
        // The page's own background behind every section, the lists included,
        // so toggling between them never changes it, and on the bar, so a
        // section scrolled under it does not show through the title.
        .background(Shuttl.bg)
        .shuttlNavigationBarBackground()
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbarContent }
        // The section first shown counts as chosen. Without this, a section
        // appearing moves the page: a video only on this phone opens on Match
        // alone, and when the run started from under its player lands, Rallies
        // becomes the default and the player is released mid-watch. The app
        // does not change the screen on its own (a finished run is announced,
        // not opened), and a section arriving is the same kind of jump. A
        // route's own `section` is already the choice, so it is never replaced.
        .onChange(of: section, initial: true) { _, shown in
            if chosenSection == nil, let shown { chosenSection = shown }
        }
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
            case .details:
                if let log = matchModel.log {
                    EditMatchDetailsSheet(
                        initial: MatchDetailsDraft(log: log), titleCap: Int(ScoreLogKt.MAX_MATCH_TITLE)
                    ) { draft in
                        matchModel.updateDetails(
                            title: draft.title, format: draft.format,
                            homePlayers: draft.homePlayers, awayPlayers: draft.awayPlayers
                        )
                    }
                } else if let video = ownedVideo {
                    EditMatchDetailsSheet(
                        initial: MatchDetailsDraft(video: video),
                        titleCap: Int(VideoMatchDetailsKt.VIDEO_MATCH_TITLE_CAP)
                    ) { draft in
                        Task {
                            await matchModel.updateVideoDetails(
                                videoId: video.videoId, title: draft.title,
                                description: draft.description ?? "", format: draft.format,
                                homePlayers: draft.homePlayers, awayPlayers: draft.awayPlayers
                            )
                        }
                    }
                }
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
        // The list's loops, run by this page while it is on screen. The root
        // list's `.task { await model.start() }` stops while a page is pushed
        // over it (seen on the simulator: the list's `start()` returns as the
        // push lands), and without loops `matchList` would freeze, so a run
        // finishing while this page is open could not make Analysis appear.
        // `start()` runs its own set of loops per call. Each set is cancelled
        // when its view is covered (this page's too, by the same rule, when it
        // pushes a rally or court marking), so one set runs at a time apart
        // from a push or pop transition, where the two overlap for its length
        // (about 0.6s measured). The overlap is harmless: both sets read the
        // same flows and write the same values.
        .task { await listModel.start() }
        // Soft failure: no metadata just means the date headline, as before. Keyed
        // on the effective video id, not a bare `.task`: a score-only match that
        // acquires a video after this page opens must pick up its metadata without
        // a re-entry, the same reason `effectiveVideoId` itself is derived. The
        // rows land in the repository's cache, which the loop below follows.
        .task(id: effectiveVideoId) {
            guard effectiveVideoId != nil else { return }
            _ = try? await SwiftInteropKt.listMatchMetadataOrNull(rally.videos)
        }
        .task {
            for await map in rally.videos.observeMatchMetadata() {
                metadataByVideoId = map
            }
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

    /// Only the section on screen is built: leaving Match releases its player,
    /// so two players never run at once. A section with nothing to draw yet
    /// (its row has not arrived) draws nothing rather than another section.
    @ViewBuilder
    private var sectionContent: some View {
        switch section {
        case .points:
            listContent
        case .match:
            if let entryId = item?.entryId {
                MatchVideoSection(rally: rally, entryId: entryId, analyzeLabel: analyzeLabel) {
                    analyzeEntry(entryId)
                }
            }
        case .rallies:
            // Rallies a device run cut and nothing in the cloud: those clips
            // never leave the phone, so they are drawn from its disk rather
            // than from rally_clips. When both exist the cloud clips win, the
            // rule the row's rally count follows.
            if clipsForMatch.isEmpty, let item, item.deviceClipCount > 0, let entryId = item.entryId {
                refreshingIfCloud(DeviceRalliesSection(localAnalysis: localAnalysis, entryId: entryId))
            } else {
                listContent
            }
        case .analysis:
            if let item, let id = MatchPageSectionKt.analysisId(item: item) {
                AnalysisSection(rally: rally, localAnalysis: localAnalysis, analysisId: id)
            }
        case nil:
            EmptyView()
        }
    }

    /// Whether the section on screen is a list whose first row carries
    /// `MatchPageLayout.rowInset` itself. See the gap in `body`.
    private var sectionIsList: Bool { section == .points || section == .rallies }

    /// What the page puts between the section row and the section: the gap,
    /// less what a list's first row carries itself, and none for Analysis,
    /// which puts it inside its scroll view.
    private var sectionTopPadding: CGFloat {
        if section == .analysis { return 0 }
        return sectionIsList
            ? MatchPageLayout.sectionGap - MatchPageLayout.rowInset
            : MatchPageLayout.sectionGap
    }

    /// The same decision the list's Analyze makes (`analyseAction(for:)`):
    /// resume a failed cloud run with saved court points, else mark the court.
    /// For the Analyze under the player and the overflow's Analyze again.
    private func analyzeEntry(_ entryId: String) {
        guard let entry = rally.localVideos.get(id: entryId) else { return }
        switch analyseAction(for: entry) {
        case .resume(let id): analyze.retry(entryId: id)
        case .markCourt(let id): navigationTarget = CourtMarkingRoute(entryId: id)
        }
    }

    private var listContent: some View { refreshingIfCloud(listBody) }

    // A pull gesture that spins and refreshes nothing is worse than no gesture: a
    // score-only match has no video, so there is nothing here for a refresh to
    // touch. Only a video-backed match keeps the old `MatchClipsView`'s
    // pull-to-refresh, on Points as well as Rallies, the device rallies
    // included: a match with a cloud video can gain cloud clips, which then
    // take their place. Match and Analysis have none: pulling on a video's
    // notes or a heatmap to refresh the clip cache would be a gesture that
    // does nothing visible. Mirrors androidApp's Rallies section.
    @ViewBuilder
    private func refreshingIfCloud(_ list: some View) -> some View {
        if effectiveVideoId != nil {
            list.refreshable {
                try? await rally.clips.refresh()
                await refreshSummary(supersede: true)
            }
        } else {
            list
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

    /// The status banner's button, as the row's own (`ClipListView.act`): by
    /// the row's entry, never looked up again by score log, because the
    /// builder may have joined the entry to its log by the log's video id.
    /// Retry is Analyze, as on the row: the same resume-or-mark-court decision.
    private func bannerAction(_ item: MatchListItem) {
        guard let entryId = item.entryId else { return }
        switch item.action {
        case .markCourt: navigationTarget = CourtMarkingRoute(entryId: entryId)
        case .analyze, .retry: analyzeEntry(entryId)
        case .none, .running, .paused: break
        }
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
            if section == .points {
                if let log = matchModel.log {
                    PointsFacet(
                        log: log, card: matchModel.card, tally: matchModel.tally,
                        points: matchModel.match?.points ?? [],
                        onScore: { scoringTarget = ScoringRoute(scoreLogId: log.id) }
                    )
                }
            } else if section == .rallies {
                // Only rallies are reordered, so the sort lives with them
                // rather than in a bar every section shares; one rally has no
                // order.
                if clipsForMatch.count > 1 {
                    RallySortRow(sort: $sort)
                }
                RalliesFacet(
                    clips: clipsForMatch,
                    summary: summary,
                    matchTitle: matchName,
                    details: detailsLine,
                    description: metadata?.description_,
                    onSummaryClick: { activeSheet = .summary },
                    onClipClick: { clipRoute = ClipRoute(id: $0.id) }
                )
            }
        }
        .listStyle(.plain)
        // The page's background between and behind the rows, as the device
        // rallies and the notes list already draw, not the one `List` paints.
        .scrollContentBackground(.hidden)
    }

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        // The chrome indicator, on every bar androidApp puts it on. See
        // `BackgroundWorkAction`.
        ToolbarItem(placement: .topBarTrailing) { BackgroundWorkAction() }
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
        // glyph per action: with share and "Add video" already in the bar, more
        // discrete items would crowd it, and two of them would read as
        // near-identical icons under VoiceOver.
        //
        // A scored match, or a filmed one that is the coach's own: both have
        // details to edit. A video on this phone that can be analyzed again has
        // that to offer. A shared match has none of these, so it gets no menu
        // at all.
        if matchModel.log != nil || isOwned || item?.canAnalyzeAgain == true {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    // First, because it is the one a coach comes here for: the
                    // new match form asks for none of this, and a video is
                    // uploaded with a name at most.
                    if matchModel.log != nil || isOwned {
                        Button {
                            activeSheet = .details
                        } label: {
                            Label("Edit details", systemImage: "pencil")
                        }
                    }
                    if let log = matchModel.log {
                        ShareLink(item: ScoreTagSummaryKt.exportMatchText(log: log)) {
                            Label("Export as text", systemImage: "doc.text")
                        }
                    }
                    // The row's own menu item, here too: the same entry, through
                    // the same resume-or-mark-court decision.
                    if let item, item.canAnalyzeAgain, let entryId = item.entryId {
                        Button(MatchListCopy.shared.MENU_ANALYZE_AGAIN) { analyzeEntry(entryId) }
                    }
                    // Offered whenever this match has a video and nothing is in
                    // flight for it - not only when the analysis disappointed. A
                    // video that found no rallies is the likeliest reason to want
                    // another one, but gating on that would leave the "Finishing
                    // up…" dead end with no action on it at all. See the
                    // 2026-08-29 design. An on-device run counts as in flight:
                    // only the row sees it, see `pageOffersVideoRemoval`.
                    if MatchPageSectionKt.pageOffersVideoRemoval(canRemoveVideo: matchModel.canRemoveVideo, item: item) {
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
/// nothing while "Add video" is also gone with no explanation. It draws the row
/// rather than a status of its own, so the two cannot say different things:
/// the same text in the same colour, the same button (Mark court, Retry or
/// Analyze), a spinner while something runs. See `showsStatusBanner`.
private struct StatusBanner: View {
    let item: MatchListItem
    let onAction: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Text(item.statusText ?? "")
                .shuttlType(ShuttlType.titleMedium)
                .foregroundStyle(item.statusIsError ? Shuttl.error : Shuttl.textSecondary)
                .lineLimit(2)
            Spacer()
            if let label = MatchListCopy.shared.actionLabel(action: item.action) {
                Button(action: onAction) {
                    Text(label)
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
            } else if item.action == .running {
                ProgressView()
                    .controlSize(.small)
                    .frame(width: 24, height: 24)
            }
        }
        // The page's gutter, which every section below it keeps.
        .padding(.horizontal, ShuttlGutter.page)
        .padding(.vertical, 12)
    }
}

/// The rally sort, at the top of the Rallies section: it only reorders rallies,
/// so it lives with them rather than in a bar every section shares. Port of
/// androidApp's `RallySortRow`.
private struct RallySortRow: View {
    @Binding var sort: ClipSort

    private var current: String {
        sort == .rallyOrder ? MatchPageCopy.shared.SORT_RALLY_ORDER : MatchPageCopy.shared.SORT_MOST_NOTES
    }

    var body: some View {
        HStack {
            Spacer()
            Menu {
                Picker(MatchPageCopy.shared.SORT, selection: $sort) {
                    Text(MatchPageCopy.shared.SORT_RALLY_ORDER).tag(ClipSort.rallyOrder)
                    Text(MatchPageCopy.shared.SORT_MOST_NOTES).tag(ClipSort.mostNotes)
                }
            } label: {
                Label(current, systemImage: "arrow.up.arrow.down")
                    .shuttlType(ShuttlType.labelMedium)
                    .foregroundStyle(Shuttl.textSecondary)
                    .lineLimit(1)
                    // The platform's minimum touch target, the label centred
                    // in it: the reason this row's text sits a few points
                    // lower than a rally row's first line.
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
            }
            // What the control does and the order it is on: the label replaces
            // the visible text for VoiceOver, so it carries both.
            .accessibilityLabel("\(MatchPageCopy.shared.SORT), \(current)")
        }
        // The page's gutter either side, no inset above or below: the touch
        // target is the row's height. No separator: it is a control over the
        // list, not one of its rows.
        .listRowInsets(EdgeInsets(top: 0, leading: ShuttlGutter.page, bottom: 0, trailing: ShuttlGutter.page))
        .listRowSeparator(.hidden)
        .listSectionSeparator(.hidden, edges: .top)
        .listRowBackground(Color.clear)
    }
}

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

struct ClipListView: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    @State private var model: ClipListModel?
    @State private var shareTarget: MatchSummary? = nil
    @State private var confirmTarget: PendingMatchAction? = nil
    @State private var intake: LocalVideoIntake
    @State private var thumbnails = LocalThumbnails()
    @State private var showImporter = false
    @State private var showRecorder = false
    @State private var progressById: [String: AnalyzeProgress] = [:]
    @State private var resultEntry: LocalVideoEntry? = nil
    @State private var navigationTarget: CourtMarkingRoute? = nil
    @State private var showLabels = false
    @State private var detailsTarget: MatchDetailsTarget? = nil
    @State private var showNewMatch = false
    @State private var deleteScoreTarget: ScoreMatchCard? = nil
    // The create-and-finish flow's single destination: the board while it is
    // being scored, then the match page once it finishes. See
    // `CreateFlowDestination`'s own doc comment.
    @State private var createFlowTarget: CreateFlowDestination? = nil

    init(rally: RallyApp, analyze: AnalyzeCoordinator) {
        self.rally = rally
        self.analyze = analyze
        _intake = State(initialValue: LocalVideoIntake(rally: rally))
    }

    var body: some View {
        VStack(spacing: 0) {
            if let intakeError = intake.error {
                ErrorBanner(message: intakeError)
            }
            Group {
                if let model {
                    content(model)
                } else {
                    SplashView()
                }
            }
        }
        .navigationTitle("MATCHES")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button("New match") { showNewMatch = true }
                    Button("Record video") {
                        if CameraRecorder.isAvailable {
                            showRecorder = true
                        } else {
                            intake.error = "Camera is not available on this device."
                        }
                    }
                    Button("Import video") { showImporter = true }
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("Add")
            }
            ToolbarItem(placement: .topBarTrailing) {
                if let model {
                    Menu {
                        // A NavigationLink here would not reliably push inside a
                        // Menu, so this is a Button paired with the
                        // .navigationDestination(isPresented:) below.
                        Button("Labels") { showLabels = true }
                        Button("Sign out") { Task { await model.signOut() } }
                        Divider()
                        Text(versionLabel)
                    } label: {
                        Image(systemName: "ellipsis")
                    }
                    .accessibilityLabel("Menu")
                }
            }
        }
        .task {
            if model == nil { model = ClipListModel(rally: rally, analyze: analyze) }
            await model?.start()
        }
        .task {
            // Drives only the auto-alert side effect. `model.localEntries` (fed by
            // its own subscription to this same flow) is the list's source of
            // truth for rendering, so this loop must not keep a second copy that
            // could momentarily disagree with it.
            for await entries in rally.localVideos.entries {
                if resultEntry == nil {
                    // A video claimed by a match is that match's row, not a
                    // standalone one - see `standalone` in `content(_:)`.
                    resultEntry = entries.filter { $0.scoreLogId == nil }
                        .first { $0.stage == .failed && !$0.resultSeen }
                }
            }
        }
        .task {
            for await map in analyze.progress {
                progressById = (map as? [String: AnalyzeProgress]) ?? [:]
            }
        }
        .sheet(item: $shareTarget) { match in
            ShareSheetView(rally: rally, videoId: match.videoId)
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
        .alert(
            (resultEntry?.failureMessage ?? "").localizedCaseInsensitiveContains("no rallies")
                ? "No rallies found" : "Analysis failed",
            isPresented: Binding(
                get: { resultEntry != nil },
                set: { if !$0 { resultEntry = nil } }
            ),
            presenting: resultEntry
        ) { entry in
            Button("Retry") {
                rally.localVideos.acknowledgeResult(id: entry.id)
                resultEntry = nil
                analyzeAction(entry)
            }
            Button("Close", role: .cancel) {
                rally.localVideos.acknowledgeResult(id: entry.id)
                resultEntry = nil
            }
        } message: { entry in
            Text(entry.failureMessage ?? "Unknown error")
        }
        .navigationDestination(item: $navigationTarget) { route in
            CourtMarkingView(rally: rally, analyze: analyze, entryId: route.entryId)
        }
        .navigationDestination(isPresented: $showLabels) {
            LabelsView(rally: rally)
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

    private func analyzeAction(_ entry: LocalVideoEntry) {
        if entry.stage == .failed && entry.keypoints != nil {
            analyze.retry(entryId: entry.id)
        } else {
            navigationTarget = CourtMarkingRoute(entryId: entry.id)
        }
    }

    @ViewBuilder
    private func content(_ model: ClipListModel) -> some View {
        // A video picked for a match is represented by that match's row
        // (MatchRow.score's `video`). Listing it again here under "On this
        // phone" would be the same match twice.
        let standalone = model.localEntries.filter { $0.scoreLogId == nil }
        return List {
            if !standalone.isEmpty {
                Section {
                    ForEach(standalone) { entry in
                        LocalVideoRowView(
                            entry: entry,
                            thumbnails: thumbnails,
                            progress: progressById[entry.id],
                            onAnalyze: { analyzeAction(entry) },
                            onRemove: {
                                intake.remove(entry: entry)
                                thumbnails.evict(id: entry.id)
                            },
                            onEditDetails: {
                                detailsTarget = MatchDetailsTarget(entry: entry, autoOpened: false)
                            }
                        )
                        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                            // Hidden mid-pipeline: removing would delete the file
                            // under the active upload and swallow the run's outcome.
                            if LocalVideoStatus.canRemove(stage: entry.stage) {
                                Button(role: .destructive) {
                                    intake.remove(entry: entry)
                                    thumbnails.evict(id: entry.id)
                                } label: {
                                    Label("Remove", systemImage: "trash")
                                }
                            }
                        }
                    }
                } header: { Shuttl.sectionLabel("On this phone") }
            }
            if let error = model.error {
                ErrorBanner(message: error)
                    .listRowInsets(EdgeInsets())
            }
            if !model.ownedRows.isEmpty {
                Section {
                    ForEach(model.ownedRows) { listRow in
                        switch listRow {
                        case .video(let match):
                            row(match, model: model)
                                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                    Button {
                                        confirmTarget = PendingMatchAction(match: match, kind: .deleteMatch)
                                    } label: {
                                        Label("Delete", systemImage: "trash")
                                    }
                                    .tint(.red)
                                }
                        case .score(let content):
                            scoreRow(content, model: model)
                                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                    Button {
                                        // A row with clips deletes two things, not one: the
                                        // video and the score log. That case gets its own
                                        // confirmation so the wording can say so.
                                        if let video = content.video {
                                            confirmTarget = PendingMatchAction(
                                                match: video,
                                                kind: .deleteBoundMatch(scoreLogId: content.card.scoreLogId)
                                            )
                                        } else {
                                            deleteScoreTarget = content.card
                                        }
                                    } label: {
                                        Label("Delete", systemImage: "trash")
                                    }
                                    .tint(.red)
                                }
                        }
                    }
                } header: { Shuttl.sectionLabel("My matches") }
            }
            if !model.shared.isEmpty {
                Section {
                    ForEach(model.shared, id: \.videoId) { match in
                        row(match, model: model)
                            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                Button {
                                    confirmTarget = PendingMatchAction(match: match, kind: .leaveShare)
                                } label: {
                                    Label("Remove", systemImage: "trash")
                                }
                                .tint(.red)
                            }
                    }
                } header: { Shuttl.sectionLabel("Shared with me") }
            }
            if standalone.isEmpty && model.ownedRows.isEmpty && model.shared.isEmpty && !model.isRefreshing {
                Text("No matches yet. Score one or record a video with the + button above.")
                    .foregroundStyle(Shuttl.textSecondary)
            }
        }
        .listStyle(.plain)
        .confirmationDialog(
            "Delete this match and every point you scored? This can't be undone.",
            isPresented: Binding(
                get: { deleteScoreTarget != nil },
                set: { if !$0 { deleteScoreTarget = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("Delete", role: .destructive) {
                if let card = deleteScoreTarget {
                    deleteScoreTarget = nil
                    Task { await model.deleteScoreMatch(scoreLogId: card.scoreLogId) }
                }
            }
            Button("Cancel", role: .cancel) { deleteScoreTarget = nil }
        }
        .confirmationDialog(
            confirmTarget?.prompt ?? "",
            isPresented: Binding(
                get: { confirmTarget != nil },
                set: { if !$0 { confirmTarget = nil } }
            ),
            titleVisibility: .visible,
            presenting: confirmTarget
        ) { pending in
            Button(pending.confirmLabel, role: .destructive) {
                // Read the target before clearing it: the Task outlives the binding.
                let videoId = pending.match.videoId
                let kind = pending.kind
                confirmTarget = nil
                Task {
                    switch kind {
                    case .deleteMatch: await model.deleteMatch(videoId: videoId)
                    case .leaveShare: await model.leaveShare(videoId: videoId)
                    case .deleteBoundMatch(let scoreLogId):
                        // Score log first: deleteMatch's refresh() syncs score
                        // logs, and if that sync landed while the score log's
                        // own delete was still on the wire, it would pull the
                        // row back from the server and resurrect the match the
                        // user just deleted. Must match Android's ordering in
                        // ClipListViewModel.deleteBoundMatch.
                        await model.deleteScoreMatch(scoreLogId: scoreLogId)
                        await model.deleteMatch(videoId: videoId)
                    }
                }
            }
            Button("Cancel", role: .cancel) { confirmTarget = nil }
        }
        .refreshable { await model.refresh() }
        .navigationDestination(for: MatchRoute.self) { route in
            MatchView(rally: rally, analyze: analyze, route: route)
        }
        .navigationDestination(isPresented: $showNewMatch) {
            NewMatchView(rally: rally) { id in
                // Straight to the board, not back to the list and not to the
                // record: creating a match courtside means being about to score it.
                showNewMatch = false
                createFlowTarget = .scoring(scoreLogId: id)
            }
        }
        .navigationDestination(item: $createFlowTarget) { target in
            // The only place this list itself pushes the board: straight from
            // creating a match, with no match page underneath yet. Once one
            // exists, `MatchView` owns pushing its own board - see its own
            // `scoringTarget` and `ScoringView.matchPageAlreadyOpen`. Both
            // stages of this flow share the one `item:` registration - see
            // `CreateFlowDestination`'s own doc comment for why.
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
        .navigationDestination(for: LocalPlayerRoute.self) { route in
            LocalPlayerView(rally: rally, analyze: analyze, entryId: route.entryId)
        }
    }

    private func row(_ match: MatchSummary, model: ClipListModel) -> some View {
        NavigationLink(value: MatchRoute(scoreLogId: nil, videoId: match.videoId)) {
            HStack(spacing: 12) {
                AsyncImage(url: model.thumbnailUrls[match.coverClipId]) { image in
                    image.resizable().aspectRatio(contentMode: .fill)
                } placeholder: {
                    Shuttl.bgTertiary
                }
                .frame(width: 96, height: 54)
                .clipped()
                .task { await model.thumbnail(forCoverOf: match) }

                VStack(alignment: .leading, spacing: 4) {
                    Text(matchRowPrimary(match))
                        .font(.body.weight(.medium))
                        .foregroundStyle(Shuttl.text)
                        .lineLimit(1)
                    Text(matchRowSecondary(match))
                        .font(.system(size: 11, weight: .medium))
                        .kerning(0.55)
                        .foregroundStyle(Shuttl.textSecondary)
                    if let description = match.description {
                        Text(description)
                            .font(.footnote)
                            .foregroundStyle(Shuttl.textSecondary)
                            .lineLimit(2)
                    }
                    if let sharer = match.sharerEmail {
                        Text("Shared by \(sharer)")
                            .font(.footnote)
                            .foregroundStyle(Shuttl.textSecondary)
                            .lineLimit(1)
                    }
                }
                Spacer()
                if match.isOwned {
                    Button {
                        shareTarget = match
                    } label: {
                        Image(systemName: "square.and.arrow.up")
                    }
                    // Same 44x44 as the score row's trailing controls: the two row
                    // kinds sit adjacent in one list, so their trailing footprints
                    // must match or the boundary between them reads as a seam.
                    .frame(width: 44, height: 44)
                    .buttonStyle(.borderless)
                }
            }
        }
    }

    /// A match scored courtside, with whatever video it has acquired. Same 96x54
    /// leading slot, same 12pt spacing and the same three text lines as
    /// `row(_:model:)`: a row a few points shorter than its neighbour reads as a bug.
    @ViewBuilder
    private func scoreRow(_ content: ScoreRowContent, model: ClipListModel) -> some View {
        let card = content.card
        // The local entry behind a court-marking / retry action, while it still
        // exists. Looked up rather than carried on ScoreRowContent because it is a
        // UI-only need - the merge itself only cares about the attach status text.
        let entry = model.localEntries.first { $0.scoreLogId == card.scoreLogId }

        NavigationLink(value: MatchRoute(scoreLogId: card.scoreLogId, videoId: card.videoId)) {
            HStack(spacing: 12) {
                Group {
                    if let video = content.video, let url = model.thumbnailUrls[video.coverClipId] {
                        AsyncImage(url: url) { image in
                            image.resizable().aspectRatio(contentMode: .fill)
                        } placeholder: {
                            Shuttl.bgTertiary
                        }
                    } else {
                        ZStack {
                            Shuttl.bgTertiary
                            Image(systemName: "list.number")
                                .foregroundStyle(Shuttl.textSecondary)
                        }
                    }
                }
                .frame(width: 96, height: 54)
                .clipped()
                // Keyed on the video id, not a plain `.task`: a row visible while
                // its clips are still arriving must re-fetch once `content.video`
                // goes from nil to non-nil, not just once on first appearance.
                .task(id: content.video?.videoId) {
                    if let video = content.video { await model.thumbnail(forCoverOf: video) }
                }

                VStack(alignment: .leading, spacing: 4) {
                    Text(card.title)
                        .font(.body.weight(.medium))
                        .foregroundStyle(Shuttl.text)
                        .lineLimit(1)
                    Text("\(card.scoreLine.uppercased()) · \(formatMatchDate(millis: card.createdAtEpochMs).uppercased())")
                        .font(.system(size: 11, weight: .medium))
                        .kerning(0.55)
                        .foregroundStyle(Shuttl.textSecondary)
                    Text(card.playersLine)
                        .font(.footnote)
                        .foregroundStyle(Shuttl.textSecondary)
                        .lineLimit(1)
                    if let attach = content.attach {
                        Text(attach.text)
                            .font(.footnote)
                            // Shuttl.error rather than a literal red: it is the
                            // same token Android reaches for (colorScheme.error)
                            // for this line.
                            .foregroundStyle(attach.kind == .failed ? Shuttl.error : Shuttl.textSecondary)
                            .lineLimit(2)
                    }
                }
                Spacer()
                switch content.attach?.kind {
                case .courtNotMarked:
                    // Padding/background live inside the label, not chained onto
                    // the Button, so the tappable area is exactly the visible
                    // pill - matching `chip` in PlaybackControlBar.swift. This row
                    // is a NavigationLink's label; a dead zone here would silently
                    // open the match instead of marking the court.
                    Button {
                        if let entry { navigationTarget = CourtMarkingRoute(entryId: entry.id) }
                    } label: {
                        Text("Mark court")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(.black)
                            .lineLimit(1)
                            .fixedSize(horizontal: true, vertical: false)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(Shuttl.accent)
                    }
                    .buttonStyle(.borderless)
                case .failed:
                    Button {
                        if let entry { analyze.retry(entryId: entry.id) }
                    } label: {
                        Text("Retry")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(.black)
                            .lineLimit(1)
                            .fixedSize(horizontal: true, vertical: false)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(Shuttl.accent)
                    }
                    .buttonStyle(.borderless)
                case .uploading, .clipping, .finishingUp:
                    // Boxed to the same 44x44 footprint as the share button
                    // below, so the trailing slot doesn't shift width the
                    // moment the pipeline finishes and the row flips to nil.
                    ProgressView()
                        .controlSize(.small)
                        .frame(width: 44, height: 44)
                case nil:
                    // Present but disabled, with the reason in its accessibility
                    // label: match_shares is keyed on video_id, so a score-only
                    // match cannot be shared. An explicit disabled state teaches
                    // the rule.
                    Button {
                        if let video = content.video { shareTarget = video }
                    } label: {
                        Image(systemName: "square.and.arrow.up")
                    }
                    .frame(width: 44, height: 44)
                    .buttonStyle(.borderless)
                    .disabled(content.video == nil)
                    .accessibilityLabel(
                        content.video != nil ? "Share match" : "Add a video to share this match"
                    )
                }
            }
        }
    }

    private var versionLabel: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        return "Version \(v) (\(b))"
    }
}

/// `autoOpened` switches the sheet's dismiss label between "Skip" and "Cancel".
struct MatchDetailsTarget: Identifiable {
    let entry: LocalVideoEntry
    let autoOpened: Bool
    var id: String { entry.id }
}

extension MatchSummary: Identifiable {
    var id: String { videoId }
}

/// A destructive match action awaiting confirmation. Both kinds share one
/// dialog; the swipe only records the target, it never acts on its own.
private struct PendingMatchAction {
    enum Kind {
        case deleteMatch
        case leaveShare
        /// A scored match that already has clips: deleting it removes the video
        /// AND the score log, so the wording below must say both.
        case deleteBoundMatch(scoreLogId: String)
    }

    let match: MatchSummary
    let kind: Kind

    var prompt: String {
        switch kind {
        case .deleteMatch:
            return "Delete this match and all its rally clips? This can't be undone."
        case .leaveShare:
            return "Remove this shared match from your list? You'll need the owner to share it again."
        case .deleteBoundMatch:
            // Mirrors Android's ClipListScreen.kt wording exactly: a bound
            // match's delete removes two things at once, the clips and the
            // scored points, and neither of the wordings above says both.
            return "Delete this match, every point you scored and all its rally clips? This can't be undone."
        }
    }

    var confirmLabel: String {
        switch kind {
        case .deleteMatch, .deleteBoundMatch: return "Delete"
        case .leaveShare: return "Remove"
        }
    }
}

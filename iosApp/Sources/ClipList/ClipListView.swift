import SwiftUI
import Shared

struct ClipListView: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    @State private var model: ClipListModel?
    @State private var shareTarget: MatchSummary? = nil
    @State private var confirmTarget: PendingMatchAction? = nil
    @State private var intake: LocalVideoIntake
    @State private var thumbnails = LocalThumbnails()
    @State private var localEntries: [LocalVideoEntry] = []
    @State private var showImporter = false
    @State private var showRecorder = false
    @State private var progressById: [String: AnalyzeProgress] = [:]
    @State private var resultEntry: LocalVideoEntry? = nil
    @State private var navigationTarget: CourtMarkingRoute? = nil
    @State private var showLabels = false
    @State private var detailsTarget: MatchDetailsTarget? = nil
    @State private var showNewMatch = false
    @State private var scoringTarget: ScoringRoute? = nil
    @State private var deleteScoreTarget: ScoreMatchCard? = nil

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
            if model == nil { model = ClipListModel(rally: rally) }
            await model?.start()
        }
        .task {
            for await entries in rally.localVideos.entries {
                localEntries = entries
                if resultEntry == nil {
                    resultEntry = entries.first { $0.stage == .failed && !$0.resultSeen }
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

    private func analyzeAction(_ entry: LocalVideoEntry) {
        if entry.stage == .failed && entry.keypoints != nil {
            analyze.retry(entryId: entry.id)
        } else {
            navigationTarget = CourtMarkingRoute(entryId: entry.id)
        }
    }

    @ViewBuilder
    private func content(_ model: ClipListModel) -> some View {
        List {
            if !localEntries.isEmpty {
                Section {
                    ForEach(localEntries) { entry in
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
                        case .score(let card):
                            scoreRow(card)
                                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                    Button { deleteScoreTarget = card } label: {
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
            if localEntries.isEmpty && model.ownedRows.isEmpty && model.shared.isEmpty && !model.isRefreshing {
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
                    }
                }
            }
            Button("Cancel", role: .cancel) { confirmTarget = nil }
        }
        .refreshable { await model.refresh() }
        .navigationDestination(for: String.self) { videoId in
            MatchClipsView(rally: rally, videoId: videoId)
        }
        .navigationDestination(for: ScoreMatchRoute.self) { route in
            ScoreMatchView(rally: rally, scoreLogId: route.scoreLogId)
        }
        .navigationDestination(for: ScoringRoute.self) { route in
            ScoringView(rally: rally, scoreLogId: route.scoreLogId)
        }
        .navigationDestination(isPresented: $showNewMatch) {
            NewMatchView(rally: rally) { id in
                // Straight to the board, not back to the list and not to the
                // record: creating a match courtside means being about to score it.
                showNewMatch = false
                scoringTarget = ScoringRoute(scoreLogId: id)
            }
        }
        .navigationDestination(item: $scoringTarget) { route in
            ScoringView(rally: rally, scoreLogId: route.scoreLogId)
        }
        .navigationDestination(for: LocalPlayerRoute.self) { route in
            LocalPlayerView(rally: rally, analyze: analyze, entryId: route.entryId)
        }
    }

    private func row(_ match: MatchSummary, model: ClipListModel) -> some View {
        NavigationLink(value: match.videoId) {
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
                    .buttonStyle(.borderless)
                }
            }
        }
    }

    /// A match scored courtside. Same 96x54 leading slot, same 12pt spacing and the
    /// same three text lines as `row(_:model:)`: a row a few points shorter than its
    /// neighbour reads as a bug.
    @ViewBuilder
    private func scoreRow(_ card: ScoreMatchCard) -> some View {
        NavigationLink(value: ScoreMatchRoute(scoreLogId: card.scoreLogId)) {
            HStack(spacing: 12) {
                ZStack {
                    Shuttl.bgTertiary
                    Image(systemName: "list.number")
                        .foregroundStyle(Shuttl.textSecondary)
                }
                .frame(width: 96, height: 54)
                .clipped()

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
                }
                Spacer()
                // Present but disabled, with the reason in its accessibility label:
                // match_shares is keyed on video_id, so a score-only match cannot be
                // shared. An explicit disabled state teaches the rule.
                Button {} label: {
                    Image(systemName: "square.and.arrow.up")
                }
                .buttonStyle(.borderless)
                .disabled(true)
                .accessibilityLabel("Add a video to share this match")
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
    }

    let match: MatchSummary
    let kind: Kind

    var prompt: String {
        switch kind {
        case .deleteMatch:
            return "Delete this match and all its rally clips? This can't be undone."
        case .leaveShare:
            return "Remove this shared match from your list? You'll need the owner to share it again."
        }
    }

    var confirmLabel: String {
        switch kind {
        case .deleteMatch: return "Delete"
        case .leaveShare: return "Remove"
        }
    }
}

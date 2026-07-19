import SwiftUI
import Shared

struct ClipListView: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    @State private var model: ClipListModel?
    @State private var shareTarget: MatchSummary? = nil
    @State private var deleteTarget: MatchSummary? = nil
    @State private var intake: LocalVideoIntake
    @State private var thumbnails = LocalThumbnails()
    @State private var localEntries: [LocalVideoEntry] = []
    @State private var showImporter = false
    @State private var showRecorder = false
    @State private var progressById: [String: AnalyzeProgress] = [:]
    @State private var resultEntry: LocalVideoEntry? = nil
    @State private var navigationTarget: CourtMarkingRoute? = nil

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
                .accessibilityLabel("Add video")
            }
            ToolbarItem(placement: .topBarTrailing) {
                if let model {
                    Menu {
                        Button("Sign out") { Task { await model.signOut() } }
                        Divider()
                        Text(versionLabel)
                    } label: {
                        Image(systemName: "ellipsis")
                    }
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
            if !model.owned.isEmpty {
                Section {
                    ForEach(model.owned, id: \.videoId) { match in
                        row(match, model: model)
                            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                Button {
                                    deleteTarget = match
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                                .tint(.red)
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
                                    Task { await model.leaveShare(videoId: match.videoId) }
                                } label: {
                                    Label("Remove", systemImage: "trash")
                                }
                                .tint(.red)
                            }
                    }
                } header: { Shuttl.sectionLabel("Shared with me") }
            }
            if localEntries.isEmpty && model.owned.isEmpty && model.shared.isEmpty && !model.isRefreshing {
                Text("No matches yet. Record one with the + button above.")
                    .foregroundStyle(Shuttl.textSecondary)
            }
        }
        .listStyle(.plain)
        .confirmationDialog(
            "Delete this match and all its rally clips? This can't be undone.",
            isPresented: Binding(
                get: { deleteTarget != nil },
                set: { if !$0 { deleteTarget = nil } }
            ),
            titleVisibility: .visible,
            presenting: deleteTarget
        ) { match in
            Button("Delete", role: .destructive) {
                let videoId = match.videoId
                deleteTarget = nil
                Task { await model.deleteMatch(videoId: videoId) }
            }
            Button("Cancel", role: .cancel) { deleteTarget = nil }
        }
        .refreshable { await model.refresh() }
        .navigationDestination(for: String.self) { videoId in
            MatchClipsView(rally: rally, videoId: videoId)
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
                    Text("Match · \(formatMatchDate(millis: match.latestCreatedAtMillis))")
                        .font(.body.weight(.medium))
                        .foregroundStyle(Shuttl.text)
                    Text("\(match.rallyCount) \(match.rallyCount == 1 ? "RALLY" : "RALLIES")")
                        .font(.system(size: 11, weight: .medium))
                        .kerning(0.55)
                        .foregroundStyle(Shuttl.textSecondary)
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

    private var versionLabel: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        return "Version \(v) (\(b))"
    }
}

extension MatchSummary: Identifiable {
    var id: String { videoId }
}

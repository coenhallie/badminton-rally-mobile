import SwiftUI
import Shared

struct ClipListView: View {
    let rally: RallyApp
    @State private var model: ClipListModel?
    @State private var shareTarget: MatchSummary? = nil
    @State private var intake: LocalVideoIntake?
    @State private var thumbnails = LocalThumbnails()
    @State private var localEntries: [LocalVideoEntry] = []
    @State private var showImporter = false
    @State private var showRecorder = false

    var body: some View {
        Group {
            if let model {
                content(model)
            } else {
                SplashView()
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
                            intake?.error = "Camera is not available on this device."
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
            if intake == nil { intake = LocalVideoIntake(rally: rally) }
            for await entries in rally.localVideos.entries {
                localEntries = entries
            }
        }
        .sheet(item: $shareTarget) { match in
            ShareSheetView(rally: rally, videoId: match.videoId)
        }
        .sheet(isPresented: $showImporter) {
            VideoPicker { tempURL, suggestedName in
                Task { await intake?.add(tempURL: tempURL, suggestedName: suggestedName, isRecording: false) }
            }
        }
        .fullScreenCover(isPresented: $showRecorder) {
            CameraRecorder { tempURL in
                Task { await intake?.add(tempURL: tempURL, suggestedName: nil, isRecording: true) }
            }
            .ignoresSafeArea()
        }
    }

    @ViewBuilder
    private func content(_ model: ClipListModel) -> some View {
        List {
            if let intakeError = intake?.error {
                ErrorBanner(message: intakeError)
                    .listRowInsets(EdgeInsets())
            }
            if !localEntries.isEmpty {
                Section {
                    ForEach(localEntries) { entry in
                        LocalVideoRowView(entry: entry, thumbnails: thumbnails) {
                            intake?.remove(entry: entry)
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
                    }
                } header: { Shuttl.sectionLabel("My matches") }
            }
            if !model.shared.isEmpty {
                Section {
                    ForEach(model.shared, id: \.videoId) { match in
                        row(match, model: model)
                    }
                } header: { Shuttl.sectionLabel("Shared with me") }
            }
            if localEntries.isEmpty && model.owned.isEmpty && model.shared.isEmpty && !model.isRefreshing {
                Text("No matches yet. Record one with the + button above.")
                    .foregroundStyle(Shuttl.textSecondary)
            }
        }
        .listStyle(.plain)
        .refreshable { await model.refresh() }
        .navigationDestination(for: String.self) { videoId in
            MatchClipsView(rally: rally, videoId: videoId)
        }
        .navigationDestination(for: LocalPlayerRoute.self) { route in
            LocalPlayerView(rally: rally, entryId: route.entryId)
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

struct LocalPlayerView: View {
    let rally: RallyApp
    let entryId: String
    var body: some View { Text("Local player — TODO Task 5") }
}

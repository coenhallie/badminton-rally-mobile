import SwiftUI
import Shared

struct ClipListView: View {
    let rally: RallyApp
    @State private var model: ClipListModel?
    @State private var shareTarget: MatchSummary? = nil

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
        .sheet(item: $shareTarget) { match in
            ShareSheetView(rally: rally, videoId: match.videoId)
        }
    }

    @ViewBuilder
    private func content(_ model: ClipListModel) -> some View {
        List {
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
            if model.owned.isEmpty && model.shared.isEmpty && !model.isRefreshing {
                Text("No matches yet.")
                    .foregroundStyle(Shuttl.textSecondary)
            }
        }
        .listStyle(.plain)
        .refreshable { await model.refresh() }
        .navigationDestination(for: String.self) { videoId in
            MatchClipsView(rally: rally, videoId: videoId)
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

// Temporary placeholder — Task 9 replaces this with the real share sheet.
struct ShareSheetView: View {
    let rally: RallyApp
    let videoId: String
    var body: some View { Text("Share — TODO Task 9") }
}

import SwiftUI
import Shared

struct MatchClipsView: View {
    let rally: RallyApp
    let videoId: String
    @State private var clips: [RallyClip] = []
    @State private var sort: ClipSort = .rallyOrder
    // Fetched here rather than handed down: the list navigates by video id alone,
    // and a soft-failing read is cheaper than threading a summary through the route.
    @State private var metadata: MatchMetadata? = nil
    @State private var summary: MatchLabelSummary? = nil
    @State private var summarySheetOpen = false
    @State private var isLoadingSummary = false
    @State private var route: ClipRoute? = nil

    private var sortedClips: [RallyClip] { sort.sorted(clips) }

    private var clipIds: [String] { clips.map(\.id) }

    private func refreshSummary() async {
        // .task and .onAppear both fire on the first appearance; one fetch is enough.
        guard !isLoadingSummary, !clips.isEmpty else { return }
        isLoadingSummary = true
        defer { isLoadingSummary = false }
        let fetched = try? await SwiftInteropKt.listForClipsOrNull(rally.annotations, clipIds: clipIds)
        // Soft failure: keep whatever is on screen and say nothing.
        guard let rows = fetched.flatMap({ $0 }) else { return }
        summary = MatchLabelSummaryKt.buildMatchLabelSummary(clips: clips, annotations: rows)
    }

    private var matchName: String? {
        // videos.title is authoritative; the clip-stamped copy keeps the name on
        // screen when the RPC is unreachable.
        metadata?.title ?? matchTitle(of: clips.map(ClipInfo.init))
    }

    private var title: String {
        if let name = matchName {
            return name.uppercased()
        }
        guard let latest = clips.map({ $0.createdAt.toEpochMilliseconds() }).max() else {
            return "RALLIES"
        }
        return "MATCH · \(formatMatchDate(millis: latest).uppercased())"
    }

    var body: some View {
        List {
            if let summary, !summary.isEmpty {
                Button { summarySheetOpen = true } label: {
                    MatchLabelStripView(summary: summary)
                }
                .buttonStyle(.plain)
            }
            if let description = metadata?.description_ {
                Text(description)
                    .font(.subheadline)
                    .foregroundStyle(Shuttl.textSecondary)
            }
            if clips.isEmpty {
                Text("No rallies in this match.")
                    .foregroundStyle(Shuttl.textSecondary)
            }
            ForEach(sortedClips, id: \.id) { clip in
                NavigationLink {
                    ClipDetailView(rally: rally, clipId: clip.id)
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(clipRowTitle(ClipInfo(clip), matchTitle: matchName))
                            .font(.body.weight(.medium))
                            .foregroundStyle(Shuttl.text)
                        Text("\(clip.durationSeconds)S · \(clip.annotationCount) NOTES")
                            .font(.system(size: 11, weight: .medium))
                            .kerning(0.55)
                            .foregroundStyle(Shuttl.textSecondary)
                    }
                }
            }
        }
        .listStyle(.plain)
        .refreshable {
            try? await rally.clips.refresh()
            await refreshSummary()
        }
        .task(id: clipIds) { await refreshSummary() }
        // Pushing ClipDetailView does not remove this view, so its .task is not
        // restarted on the way back, and adding a note does not change clipIds.
        // Without this the strip would go stale exactly where it matters most.
        .onAppear { Task { await refreshSummary() } }
        .sheet(isPresented: $summarySheetOpen) {
            if let summary {
                MatchSummarySheet(
                    summary: summary,
                    topRallyName: summary.topRally.map {
                        topRallyName(
                            clipId: $0.clipId, rallyIndex: $0.rallyIndex,
                            clips: clips.map(ClipInfo.init), matchTitle: matchName
                        )
                    },
                    onTopRally: {
                        if let top = summary.topRally { route = ClipRoute(id: top.clipId) }
                    }
                )
                .presentationDetents([.medium, .large])
            }
        }
        .navigationDestination(item: $route) { route in
            ClipDetailView(rally: rally, clipId: route.id)
        }
        .task {
            // Soft failure: no metadata just means the date headline, as before.
            let rows = try? await SwiftInteropKt.listMatchMetadataOrNull(rally.videos)
            metadata = rows?.first { $0.videoId == videoId }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
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
        .task {
            for await latest in rally.clips.observeClips() {
                clips = latest.filter { $0.videoId == videoId }
            }
        }
    }
}

/// `navigationDestination(item:)` needs an Identifiable, and the sheet's
/// "most labelled" row pushes a clip programmatically rather than through a
/// NavigationLink.
private struct ClipRoute: Identifiable, Hashable {
    let id: String
}

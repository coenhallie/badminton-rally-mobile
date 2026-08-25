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
    // A superseded fetch must not write its result: the awaited Kotlin bridge does
    // not observe Swift task cancellation, so a replaced .task keeps running to
    // completion. The generation it captured tells it to drop what it got.
    @State private var summaryGeneration = 0
    @State private var route: ClipRoute? = nil
    // Set while the summary sheet is closing; the push happens in the sheet's
    // onDismiss rather than inline with dismiss(), because a navigationDestination
    // on the view that is presenting a sheet can drop a push made mid-dismissal.
    @State private var pendingTopRallyClipId: String? = nil

    private var sortedClips: [RallyClip] { sort.sorted(clips) }

    private var clipIds: [String] { clips.map(\.id) }

    /// `supersede` is for the triggers that must never be swallowed: a changed clip
    /// set and an explicit pull to refresh. Only `.onAppear` defers to a fetch that
    /// is already running, which mirrors the Android view model, where the clip-set
    /// collector restarts the load and only refresh() consults the guard.
    private func refreshSummary(supersede: Bool = false) async {
        let target = clips
        guard !target.isEmpty else {
            // The match's clips were pruned. Drop the summary rather than leave a
            // rollup of clips that are gone, matching MatchSummaryViewModel.
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
            if let summary, !summary.isEmpty, !clips.isEmpty {
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
            await refreshSummary(supersede: true)
        }
        .task(id: clipIds) { await refreshSummary(supersede: true) }
        // Pushing ClipDetailView does not remove this view, so its .task is not
        // restarted on the way back, and adding a note does not change clipIds.
        // Without this the strip would go stale exactly where it matters most.
        .onAppear { Task { await refreshSummary() } }
        .onChange(of: summary?.isEmpty) { _, isEmpty in
            if isEmpty != false { summarySheetOpen = false }
        }
        .sheet(isPresented: $summarySheetOpen, onDismiss: {
            guard let clipId = pendingTopRallyClipId else { return }
            pendingTopRallyClipId = nil
            route = ClipRoute(id: clipId)
        }) {
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
                        // Parity with Android, which looks the clip up and no-ops when
                        // it is gone rather than pushing a detail view for a clip that
                        // no longer exists.
                        if let top = summary.topRally,
                           clips.contains(where: { $0.id == top.clipId }) {
                            pendingTopRallyClipId = top.clipId
                        }
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

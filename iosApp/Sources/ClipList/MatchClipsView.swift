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

    private var sortedClips: [RallyClip] { sort.sorted(clips) }

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
        .refreshable { try? await rally.clips.refresh() }
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

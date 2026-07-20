import SwiftUI
import Shared

struct MatchClipsView: View {
    let rally: RallyApp
    let videoId: String
    @State private var clips: [RallyClip] = []
    @State private var sort: ClipSort = .rallyOrder

    private var sortedClips: [RallyClip] { sort.sorted(clips) }

    private var title: String {
        guard let latest = clips.map({ $0.createdAt.toEpochMilliseconds() }).max() else {
            return "RALLIES"
        }
        return "MATCH · \(formatMatchDate(millis: latest).uppercased())"
    }

    var body: some View {
        List {
            if clips.isEmpty {
                Text("No rallies in this match.")
                    .foregroundStyle(Shuttl.textSecondary)
            }
            ForEach(sortedClips, id: \.id) { clip in
                NavigationLink {
                    ClipDetailView(rally: rally, clipId: clip.id)
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(clip.title ?? "Rally #\(clip.rallyIndex)")
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

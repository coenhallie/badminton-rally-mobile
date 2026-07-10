import SwiftUI
import Shared

struct MatchClipsView: View {
    let rally: RallyApp
    let videoId: String
    @State private var clips: [RallyClip] = []

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
            ForEach(clips, id: \.id) { clip in
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
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            for await latest in rally.clips.observeClips() {
                clips = latest
                    .filter { $0.videoId == videoId }
                    .sorted { $0.rallyIndex < $1.rallyIndex }
            }
        }
    }
}

// Temporary placeholder — Task 8 replaces this with the real clip detail screen.
struct ClipDetailView: View {
    let rally: RallyApp
    let clipId: String
    var body: some View { Text("Detail — TODO Task 8") }
}

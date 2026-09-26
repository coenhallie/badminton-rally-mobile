import Shared
import SwiftUI

/// The Match section as its own pushed screen, for the list rows that open a
/// local video until the match page hosts `MatchVideoSection` itself. The bar
/// carries the video's name and its Analyze action; everything under it is the
/// section's.
struct LocalPlayerView: View {
    let rally: RallyApp
    let analyze: AnalyzeCoordinator
    /// Routed through only so the court marking this screen can push offers the
    /// same two targets it offers everywhere else. A coach who marks a court
    /// from the player must not get a cloud-only flow because of where they
    /// started.
    let localAnalysis: LocalAnalysisRunner?
    let entryId: String
    @Environment(\.dismiss) private var dismiss
    @State private var liveEntry: LocalVideoEntry?
    @State private var courtTarget: CourtMarkingRoute? = nil

    var body: some View {
        MatchVideoSection(rally: rally, entryId: entryId, analyzeLabel: nil, onAnalyze: {})
            .navigationTitle(liveEntry.map { $0.title ?? $0.displayName } ?? "")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                // The chrome indicator, on every bar androidApp puts it on. See
                // `BackgroundWorkAction`.
                ToolbarItem(placement: .topBarTrailing) { BackgroundWorkAction() }
                ToolbarItem(placement: .topBarTrailing) {
                    // Live stage, not a load-time snapshot: after "Start
                    // Analysis" the button must disappear while the pipeline runs.
                    // And the device's own liveness: the stage rules out a cloud
                    // run in flight, but a device run never moves it, so this
                    // button would sit here live over the run it already started.
                    if let stage = liveEntry?.stage, LocalVideoStatus.canAnalyze(
                        stage: stage, device: localAnalysis?.state(for: entryId) ?? .idle
                    ) {
                        Button(LocalVideoStatus.analyzeButtonLabel(stage: stage)) {
                            courtTarget = CourtMarkingRoute(entryId: entryId)
                        }
                        .shuttlType(ShuttlType.labelMedium)
                        .lineLimit(1)
                        .fixedSize(horizontal: true, vertical: false)
                    }
                }
            }
            .navigationDestination(item: $courtTarget) { route in
                CourtMarkingView(
                    rally: rally, analyze: analyze,
                    localAnalysis: localAnalysis, entryId: route.entryId
                )
            }
            .task {
                // Keep the entry live (Android observes it the same way): the
                // stage drives the Analyze button, and analysis started from this
                // screen must be reflected here, not just on the home list.
                guard let entry = rally.localVideos.get(id: entryId) else {
                    dismiss()   // entry vanished - mirror Android's popBackStack
                    return
                }
                liveEntry = entry
                for await entries in rally.localVideos.entries {
                    guard let entry = entries.first(where: { $0.id == entryId }) else {
                        dismiss()   // removed (e.g. analysis succeeded) - pop back
                        return
                    }
                    liveEntry = entry
                }
            }
    }
}

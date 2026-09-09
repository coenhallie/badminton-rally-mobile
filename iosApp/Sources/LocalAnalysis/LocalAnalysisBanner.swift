import Shared
import SwiftUI

/// What the on-device pipeline PRODUCED. Progress lives in the bar.
/// Port of Android's `LocalAnalysisBanner`.
///
/// Exists so the two pipelines can be compared without a debugger attached: an
/// on-device run takes minutes, and "did it work" is otherwise only answerable
/// from the console. Shows the numbers worth comparing against a cloud run of
/// the same video - rallies found, shuttle visibility, and how long it took -
/// and routes to the clips themselves.
///
/// It deliberately renders nothing while a run is in flight. A card that pushed
/// the whole screen down for the nine minutes an analysis takes cost more space
/// than the one number it carried, and that number is in the bar, where it is
/// visible from every screen rather than only this one.
///
/// The one departure from androidApp: the clips are a route rather than a list
/// of buttons over a dialog. `LocalClipsView` already draws every cut rally with
/// its bounds and plays it looping, which is what the comparison actually needs,
/// and androidApp's inline buttons predate having such a screen at all.
struct LocalAnalysisBanner: View {
    let runner: LocalAnalysisRunner
    var onOpenClips: (String) -> Void = { _ in }
    var onOpenHeatmap: (String) -> Void = { _ in }

    /// Finished or failed only: in-flight states are the indicator's.
    ///
    /// Sorted by entry id so two settled runs do not swap places on an unrelated
    /// redraw.
    private var settled: [(String, LocalAnalysisState)] {
        runner.states
            .filter { _, state in
                if case .done = state { return true }
                if case .failed = state { return true }
                return false
            }
            .sorted { $0.key < $1.key }
            .map { ($0.key, $0.value) }
    }

    var body: some View {
        if !settled.isEmpty {
            VStack(spacing: 8) {
                ForEach(settled, id: \.0) { entryId, state in
                    card(entryId: entryId, state: state)
                }
            }
            .padding(.horizontal, ShuttlGutter.page)
            .padding(.top, 12)
        }
    }

    @ViewBuilder
    private func card(entryId: String, state: LocalAnalysisState) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("On-device analysis")
                .shuttlType(ShuttlType.labelMedium)
                .foregroundStyle(Shuttl.textHeading)

            switch state {
            case .done(let done):
                Text(Self.summary(done))
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.text)
                if !done.clips.isEmpty {
                    Button("Clips on this phone (\(done.clips.count))") { onOpenClips(entryId) }
                        .buttonStyle(.plain)
                        .foregroundStyle(Shuttl.accentDark)
                        .shuttlType(ShuttlType.labelMedium)
                }
                // Only when there is a track behind it: a button that opens an
                // empty court is worse than no button, because it reads as the
                // analysis having failed silently.
                if !done.playerTrack.samples.isEmpty {
                    Button("Player heatmap (\(done.playerTrack.samples.count) positions)") {
                        onOpenHeatmap(entryId)
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(Shuttl.accentDark)
                    .shuttlType(ShuttlType.labelMedium)
                }
            case .failed(let message):
                Text("Failed: \(message)")
                    .shuttlType(ShuttlType.bodySmall)
                    .foregroundStyle(Shuttl.error)
                    .lineLimit(3)
            default:
                // Filtered out above; the compiler still wants them.
                EmptyView()
            }

            Button("Dismiss") { runner.clear(entryId: entryId) }
                .buttonStyle(.plain)
                .foregroundStyle(Shuttl.textSecondary)
                .shuttlType(ShuttlType.labelMedium)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 20)
        .padding(.vertical, 16)
        .background(Shuttl.bgSecondary, in: RoundedRectangle(cornerRadius: ShuttlRadius.large))
    }

    /// The numbers a cloud run of the same video can be held against, on one
    /// line. Shared wording with androidApp's, down to the order.
    static func summary(_ done: LocalAnalysisState.Done) -> String {
        "\(done.rallies) rallies, \(done.clips.count) clips, "
            + "shuttle in \(done.shuttleVisible)/\(done.totalFrames) frames, "
            + "\(Int(done.elapsedSeconds.rounded()))s"
    }
}

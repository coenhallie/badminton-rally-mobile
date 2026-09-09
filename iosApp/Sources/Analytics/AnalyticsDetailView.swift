import Shared
import SwiftUI

/// A track to draw and the frame rate it was sampled at, which must travel
/// together.
struct HeatmapSource {
    let track: PlayerTrack
    let fps: Double
}

/// Which track a heatmap should draw: the one still in memory, the one on disk,
/// or neither. Port of Android's `heatmapSource`.
///
/// A finished run is preferred, but only when it actually carries samples. A run
/// that asked for no pose metric completes with an EMPTY `PlayerTrack`, and the
/// runner only writes one to disk when there are samples, so preferring the
/// in-memory run blindly would replace a perfectly good stored heatmap with "No
/// pose data for this video." Court marking seeds its metrics to rally clips
/// alone, so a pose-less run is the DEFAULT: "pose last week, clips today, open
/// the heatmap" is an ordinary coach path, not a corner.
///
/// Second, quieter consequence, kept deliberately. An empty track also means a
/// pose run that found nobody on the near court, and such a run saves nothing.
/// So a coach who waits on that run sees the PREVIOUS run's heatmap rather than
/// "The player was not found...". The alternative is discarding a good heatmap
/// because a later run failed, which is worse; the run's own outcome is reported
/// where runs are reported, not here.
///
/// Returning the pair is the point: the fps belongs to the track it was measured
/// with, and picking each independently paired them by coincidence.
func heatmapSource(
    done: LocalAnalysisState.Done?,
    stored: PlayerTrackStore.Stored?
) -> HeatmapSource? {
    if let done, !done.playerTrack.samples.isEmpty {
        return HeatmapSource(track: done.playerTrack, fps: done.fps)
    }
    if let stored {
        return HeatmapSource(track: stored.track, fps: stored.fps)
    }
    return nil
}

/// What one analysed match has to show, reached from a ready row on the
/// Analytics list. Port of Android's `AnalyticsDetailScreen`.
///
/// **Heatmap only, for now.** Android offers a pill tab row over Heatmap, Base
/// and Skeleton, drawn only when more than one of them has content, and falls
/// back to a plain "HEATMAP" heading when one does. This screen is that
/// single-panel case, which is not a reduced port but the same screen in the
/// state Android draws whenever the other two panels have nothing behind them.
/// The two that are missing - the per-rally base position and the skeleton over
/// playback - each land by flipping one condition in `availablePanels`.
struct AnalyticsDetailView: View {
    let rally: RallyApp
    let localAnalysis: LocalAnalysisRunner?
    let entryId: String

    var body: some View {
        let entry = rally.localVideos.get(id: entryId)
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                // Styled as the list's own section label, not as a title: a
                // coach arrives here in one tap from that list, and two
                // treatments of the same thing across those two screens reads
                // as two different kinds of heading.
                Text("HEATMAP")
                    .shuttlType(ShuttlType.labelSmall)
                    .foregroundStyle(Shuttl.textSecondary)
                    .padding(.horizontal, CourtHeatmapView.gutter)
                    .padding(.vertical, 12)
                panel
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Shuttl.bg)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 0) {
                    // The list's own naming: the coach's title, else the file
                    // name.
                    Text(entry?.title ?? entry?.displayName ?? "Analysis")
                        .shuttlType(ShuttlType.titleMedium)
                        .foregroundStyle(Shuttl.textHeading)
                        .lineLimit(1)
                    if let entry {
                        Text(detailSubtitle(entry))
                            .shuttlType(ShuttlType.bodySmall)
                            .foregroundStyle(Shuttl.textTertiary)
                            .lineLimit(1)
                    }
                }
            }
        }
    }

    /// One entry's court heatmap, or an honest line saying why there is none.
    ///
    /// In memory if the run is still loaded, from disk otherwise. A pose run
    /// costs minutes to half an hour, so losing its result to a termination and
    /// asking for another one is not an option.
    ///
    /// The disk is read once per entry even when a run is in memory, because
    /// that run may be a pose-less one whose empty track must fall through. This
    /// is a detail screen reached by an explicit tap, so it is one parse per
    /// opening, not the per-row cost the Analytics list had to avoid.
    @ViewBuilder
    private var panel: some View {
        let done: LocalAnalysisState.Done? = {
            if case .done(let done) = localAnalysis?.state(for: entryId) ?? .idle { return done }
            return nil
        }()
        let stored = localAnalysis?.storedTrack(entryId: entryId)
        if let source = heatmapSource(done: done, stored: stored) {
            CourtHeatmapView(track: source.track, fps: source.fps)
        } else {
            // A run's state lives in memory, so it is gone after a termination.
            // Said plainly rather than drawing an empty court, which would read
            // as a player who never moved.
            PanelMessage(text: "This analysis is no longer loaded. Run it again to see the heatmap.")
        }
    }
}

/// The bar's second line for one entry: how long it runs and when it was added.
///
/// The mock's second line names a rally ("Rally 12 of 46 · Aug 21"); this screen
/// shows the whole video, so its second line says which video.
func detailSubtitle(_ entry: LocalVideoEntry) -> String {
    let duration = LocalVideoLogic.formatDuration(ms: entry.durationMs)
    let date = formatMatchDate(millis: entry.addedAtEpochMs)
    return "\(duration) · \(date)"
}

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
/// A pill tab row over the panels that have content, or the plain "HEATMAP"
/// heading when only one does - which is the ordinary case, since a run that was
/// not asked for a skeleton leaves none. Which panels those are is
/// `availablePanels`, in `shared`, so a tab offered on one phone and not the
/// other cannot happen.
///
/// **Base is still androidApp's alone.** `availablePanels` is asked for it with
/// `hasBoundedClips: false` rather than being given a two-case iOS variant that
/// structurally disagrees with androidApp's three - the tab appears here the day
/// `BasePositionPanel` is ported and that argument becomes real.
struct AnalyticsDetailView: View {
    let rally: RallyApp
    let localAnalysis: LocalAnalysisRunner?
    let entryId: String

    @State private var chosen: AnalyticsPanel = .heatmap

    /// What this entry has, read once per opening rather than per redraw.
    ///
    /// `storedTrack` is a full parse - about a megabyte for a 30-minute match -
    /// and `hasStoredSkeleton` a file header. Resolved in a computed property
    /// they would run several times per body evaluation, once for the tab set,
    /// again for the tab held to it, and again for the panel: the same per-redraw
    /// file cost `storedTrackIds` exists to keep off the Analytics list.
    @State private var source: HeatmapSource? = nil
    @State private var hasSkeleton = false

    private var panels: [AnalyticsPanel] {
        AnalyticsPanelKt.availablePanels(
            hasTrack: source != nil,
            // Base is not ported; see the note above.
            hasBoundedClips: false,
            hasSkeleton: hasSkeleton
        )
    }

    /// The coach's pick held to the tabs on screen, the same way the metrics
    /// strip holds its own: a stored skeleton can go while this screen is up.
    private var panel: AnalyticsPanel { panels.contains(chosen) ? chosen : .heatmap }

    /// Whether the run for THIS entry has stopped, whichever way it stopped.
    ///
    /// Cheap - a dictionary lookup - and read on every redraw on purpose: it is
    /// what tells this screen that a run finishing behind it has written a track
    /// or a skeleton, which androidApp gets from collecting `done`. The reload it
    /// triggers is the expensive part, and that is gated on this changing.
    private var runSettled: Bool {
        switch localAnalysis?.state(for: entryId) ?? .idle {
        case .done, .failed: return true
        default: return false
        }
    }

    var body: some View {
        let entry = rally.localVideos.get(id: entryId)
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if panels.count > 1 {
                    ShuttlPillTabs(
                        labels: panels.map(\.label),
                        selectedIndex: panels.firstIndex(of: panel) ?? 0,
                        onSelect: { chosen = panels[$0] },
                        accessibilityLabel: "Analysis panel"
                    )
                    .padding(.horizontal, CourtHeatmapView.gutter)
                    .padding(.top, 8)
                    .padding(.bottom, 16)
                } else {
                    // Styled as the list's own section label, not as a title: a
                    // coach arrives here in one tap from that list, and two
                    // treatments of the same thing across those two screens
                    // reads as two different kinds of heading.
                    Text("HEATMAP")
                        .shuttlType(ShuttlType.labelSmall)
                        .foregroundStyle(Shuttl.textSecondary)
                        .padding(.horizontal, CourtHeatmapView.gutter)
                        .padding(.vertical, 12)
                }
                switch panel {
                case .heatmap:
                    heatmap
                case .skeleton:
                    if let localAnalysis {
                        SkeletonPanel(
                            entryId: entryId,
                            videoRelativePath: entry?.uri,
                            runner: localAnalysis,
                            prefs: rally.playbackPrefs,
                            racketArmPrefs: rally.racketArmPrefs
                        )
                    }
                case .base:
                    // Unreachable while `hasBoundedClips` is false above, and
                    // stated rather than defaulted so porting the panel is a
                    // compiler-visible edit rather than a silently blank tab.
                    PanelMessage(text: "Base position is not available on iPhone yet.")
                default:
                    EmptyView()
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Shuttl.bg)
        .task { reload() }
        .onChange(of: runSettled) { _, _ in reload() }
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

    /// Re-reads what this entry has. One parse of the track and one header read,
    /// on opening and whenever a run settles - never per redraw.
    ///
    /// The disk is read even when a run is in memory, because that run may be a
    /// pose-less one whose empty track has to fall through to a stored one; see
    /// `heatmapSource`.
    private func reload() {
        let done: LocalAnalysisState.Done? = {
            if case .done(let done) = localAnalysis?.state(for: entryId) ?? .idle { return done }
            return nil
        }()
        source = heatmapSource(done: done, stored: localAnalysis?.storedTrack(entryId: entryId))
        hasSkeleton = localAnalysis?.hasStoredSkeleton(entryId: entryId) ?? false
    }

    /// One entry's court heatmap, or an honest line saying why there is none.
    ///
    /// In memory if the run is still loaded, from disk otherwise - `reload`
    /// resolves which. A pose run costs minutes to half an hour, so losing its
    /// result to a termination and asking for another one is not an option.
    @ViewBuilder
    private var heatmap: some View {
        if let source {
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

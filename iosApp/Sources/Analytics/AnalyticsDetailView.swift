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

/// The rallies a run cut, as the spans the base positions are measured over.
/// Port of the `remember` in Android's `BasePositionPanel`.
///
/// A run still in memory may carry clips the sidecar on disk does not yet hold,
/// so it wins - but only when it actually cut some. A run asked for pose alone
/// completes with NO clips, and preferring it blindly would drop the windows a
/// previous run left behind, which is the same shape of mistake `heatmapSource`
/// exists to avoid on the track.
///
/// Clips recovered by filename have no bounds and reach `basePositions` as
/// unbounded windows, which it skips. That is the honest outcome: the panel says
/// the windows were not kept rather than measuring a median over the whole match
/// and calling it a rally.
func rallyWindows(done: [PlayerTrackStore.Clip], stored: [PlayerTrackStore.Clip]) -> [RallyWindow] {
    (done.isEmpty ? stored : done).map {
        RallyWindow(index: Int32($0.index), startSeconds: $0.startSeconds, endSeconds: $0.endSeconds)
    }
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
/// The panels are `HeatmapPanel`, `BasePositionPanel` and `SkeletonPanel` on
/// androidApp. Here the first is `CourtHeatmapView` and the other two are their
/// own views, and this screen resolves what all three read - the track, the
/// rally windows, whether a skeleton was kept - so a tab and the panel behind it
/// cannot disagree about which run they are showing.
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
    /// The cut rallies as spans, for the Base panel and for the tab that offers
    /// it. Read here rather than in the panel for the same reason `source` is:
    /// `storedClips` parses a sidecar off disk.
    @State private var windows: [RallyWindow] = []

    private var panels: [AnalyticsPanel] {
        AnalyticsPanelKt.availablePanels(
            hasTrack: source != nil,
            hasBoundedClips: windows.contains { $0.isBounded },
            hasSkeleton: hasSkeleton,
            // A cloud analysis lands on a phone that never held the footage,
            // so the skeleton tab has nothing to overlay. Same resolution
            // SkeletonPanel uses for the file it plays.
            hasVideo: rally.localVideos.get(id: entryId)?.uri != nil
        )
    }

    /// The coach's pick held to the tabs on screen, the same way the metrics
    /// strip holds its own: a stored skeleton can go while this screen is up.
    private var panel: AnalyticsPanel { panels.contains(chosen) ? chosen : .heatmap }

    /// Whether the run for THIS entry has stopped, whichever way it stopped.
    ///
    /// It is what tells this screen that a run finishing behind it has written a
    /// track or a skeleton, which androidApp gets from collecting `done`. The
    /// reload it triggers is the expensive part, and that is gated on this
    /// changing.
    ///
    /// Read from `settledRuns` and not from `state(for:)`: a state read
    /// subscribes this whole screen to every progress write of every run, and
    /// the redraw that follows rebuilt the panel behind it - the Base panel's
    /// rally list included - at the rate of the progress bar.
    private var runSettled: Bool {
        localAnalysis?.settledRuns.contains(entryId) ?? false
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
                    .padding(.horizontal, ShuttlGutter.page)
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
                        .padding(.horizontal, ShuttlGutter.page)
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
                    BasePositionPanel(source: source, windows: windows)
                default:
                    EmptyView()
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(Shuttl.bg)
        .task { await reload() }
        .onChange(of: runSettled) { _, _ in Task { await reload() } }
        .navigationBarTitleDisplayMode(.inline)
        .shuttlNavigationBarBackground()
        .toolbar {
            // The chrome indicator, on every bar androidApp puts it on. See
            // `BackgroundWorkAction`.
            ToolbarItem(placement: .topBarTrailing) { BackgroundWorkAction() }
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
    /// What the three stored reads produced, carried back from the task that
    /// did them.
    private struct Stored {
        let track: PlayerTrackStore.Stored?
        let hasSkeleton: Bool
        let clips: [PlayerTrackStore.Clip]
    }

    private func reload() async {
        let done: LocalAnalysisState.Done? = {
            if case .done(let done) = localAnalysis?.state(for: entryId) ?? .idle { return done }
            return nil
        }()
        // The three disk reads off the main actor. `storedTrack`, `storedClips`
        // and `hasStoredSkeleton` are `nonisolated` precisely so they can run
        // there; calling them from a main-actor task put the megabyte track
        // parse, the clips sidecar and the skeleton header back on the main
        // thread on every open and every time a run settled behind this screen.
        let runner = localAnalysis
        let id = entryId
        let stored = await Task.detached(priority: .userInitiated) { () -> Stored in
            Stored(
                track: runner?.storedTrack(entryId: id),
                hasSkeleton: runner?.hasStoredSkeleton(entryId: id) ?? false,
                clips: runner?.storedClips(entryId: id) ?? []
            )
        }.value

        source = heatmapSource(done: done, stored: stored.track)
        hasSkeleton = stored.hasSkeleton
        windows = rallyWindows(done: done?.clips ?? [], stored: stored.clips)
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

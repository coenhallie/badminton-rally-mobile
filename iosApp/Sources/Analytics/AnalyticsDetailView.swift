import Shared
import SwiftUI

/// The tracks to draw and the frame rate they were sampled at, which must
/// travel together.
///
/// A list because a cloud analysis carries both players. Returning the pair is
/// still the point: the fps belongs to the tracks it was measured with, and
/// picking each independently paired them by coincidence.
struct HeatmapSource {
    let tracks: [PlayerTrackStore.SideTrack]
    let fps: Double
}

/// Near or Far, shown only where both were found.
///
/// Camera-relative labels. `videos.player_labels` carries the coach's own names
/// and Phase 2's thumbnails; using them is out of scope (spec 10), and a label
/// saying "Anna" over a track the gates assigned by net side would claim an
/// identification this pipeline does not make.
///
/// Mirrors androidApp's `SideToggle` in HeatmapPanel.kt, label for label.
struct SideToggle: View {
    let sides: [CourtSide]
    let selected: CourtSide
    let onSelect: (CourtSide) -> Void

    var body: some View {
        ShuttlPillTabs(
            labels: sides.map { $0 == .near ? "Near" : "Far" },
            selectedIndex: max(0, sides.firstIndex(of: selected) ?? 0),
            onSelect: { onSelect(sides[$0]) },
            accessibilityLabel: "Which player",
            // Secondary: the row belongs to the panel it sits in, under the
            // Analysis section's own panel row.
            secondary: true
        )
    }
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
    // Only sides anyone was actually found on. A toggle whose second option
    // draws an empty court is a control that cannot usefully be actuated.
    var fromDisk: HeatmapSource?
    if let stored {
        let drawable = stored.tracks.filter { !$0.track.samples.isEmpty }
        if !drawable.isEmpty { fromDisk = HeatmapSource(tracks: drawable, fps: stored.fps) }
    }
    // A device run produces the near player only, so this is always a single
    // track. The empty check is load-bearing: see above.
    var fromMemory: HeatmapSource?
    if let done, !done.playerTrack.samples.isEmpty {
        fromMemory = HeatmapSource(
            tracks: [PlayerTrackStore.SideTrack(side: .near, track: done.playerTrack)],
            fps: done.fps
        )
    }
    // Cloud first, matching `storedTrack`'s own rule: it comes from a large
    // model on a GPU and carries both players, so a near-only device run
    // finishing beside it must not take the court back. Below that the
    // in-memory run wins, because it is the newest truth for the local source
    // and lands in memory before it lands on disk.
    if stored?.source == .cloud, let fromDisk { return fromDisk }
    return fromMemory ?? fromDisk
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

/// The Analysis section as its own pushed screen, reached from a ready row on
/// the Analytics list until the match page hosts `AnalysisSection` itself. The
/// bar carries the match's name and its second line; everything under it is the
/// section's.
struct AnalyticsDetailView: View {
    let rally: RallyApp
    let localAnalysis: LocalAnalysisRunner?
    let entryId: String
    /// The list's name for this match, for when there is no entry to read one
    /// from - which is every cloud-analysed match, since the run retires it.
    var fallbackTitle: String? = nil

    var body: some View {
        let entry = rally.localVideos.get(id: entryId)
        AnalysisSection(rally: rally, localAnalysis: localAnalysis, analysisId: entryId)
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
                        Text(entry?.title ?? entry?.displayName ?? fallbackTitle ?? "Analysis")
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

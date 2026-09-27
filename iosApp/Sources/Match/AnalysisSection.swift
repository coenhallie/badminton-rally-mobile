import Shared
import SwiftUI

/// The Analysis section: what one analysed match has to show, on the match
/// page's Analysis toggle. Port of androidApp's `AnalysisSection`.
///
/// A pill tab row over the panels that have content, or the plain "HEATMAP"
/// heading when only one does - which is the ordinary case, since a run that was
/// not asked for a skeleton leaves none. Gated the same way the match page gates
/// its section row: a pill must have something behind it. Which panels those are
/// is `availablePanels`, in `shared`, so a panel offered on one phone and not the
/// other cannot happen.
///
/// The panels are `HeatmapPanel`, `BasePositionPanel` and `SkeletonPanel` on
/// androidApp. Here the first is `CourtHeatmapView` and the other two are their
/// own views, and this section resolves what all three read - the track, the
/// rally windows, whether a skeleton was kept - so a pill and the panel behind it
/// cannot disagree about which run they are showing.
///
/// `analysisId` is `analysisId(item)`: the heatmap's own id, else the entry.
struct AnalysisSection: View {
    let rally: RallyApp
    let localAnalysis: LocalAnalysisRunner?
    let analysisId: String

    @State private var chosen: AnalyticsPanel = .heatmap

    /// What this match has, read once per opening rather than per redraw.
    ///
    /// `storedTrack` is a full parse - about a megabyte for a 30-minute match -
    /// and `hasStoredSkeleton` a file header. Resolved in a computed property
    /// they would run several times per body evaluation, once for the panel set,
    /// again for the panel held to it, and again for the panel itself: the same
    /// per-redraw file cost `storedTrackIds` exists to keep off the match list.
    @State private var source: HeatmapSource? = nil
    /// Held by side rather than by index, so a reload that reordered or dropped
    /// a side cannot silently swap which player the court is showing.
    @State private var side: CourtSide = .near
    @State private var hasSkeleton = false
    /// The cut rallies as spans, for the Base panel and for the pill that offers
    /// it. Read here rather than in the panel for the same reason `source` is:
    /// `storedClips` parses a sidecar off disk.
    @State private var windows: [RallyWindow] = []
    /// `rally_clips` for this video: the rally truth for a CLOUD-analysed one,
    /// which cuts nothing on this phone. Without these the Base panel could never
    /// appear for a cloud run, however good its track.
    @State private var cloudClips: [RallyClip] = []

    private var panels: [AnalyticsPanel] {
        AnalyticsPanelKt.availablePanels(
            hasTrack: source != nil,
            hasBoundedClips: windows.contains { $0.isBounded },
            hasSkeleton: hasSkeleton,
            // A cloud analysis lands on a phone that never held the footage,
            // so the Skeleton panel has nothing to overlay. Same resolution
            // SkeletonPanel uses for the file it plays.
            hasVideo: rally.localVideos.get(id: analysisId)?.uri != nil
        )
    }

    /// The coach's pick held to the panels on offer, the same way the metrics
    /// strip holds its own: a stored skeleton can go while this section is up.
    private var panel: AnalyticsPanel { panels.contains(chosen) ? chosen : .heatmap }

    /// Whether the run for THIS match has stopped, whichever way it stopped.
    ///
    /// It is what tells this section that a run finishing behind it has written
    /// a track or a skeleton, which androidApp gets from collecting `done`. The
    /// reload it triggers is the expensive part, and that is gated on this
    /// changing.
    ///
    /// Read from `settledRuns` and not from `state(for:)`: a state read
    /// subscribes this whole section to every progress write of every run, and
    /// the redraw that follows rebuilt the panel behind it - the Base panel's
    /// rally list included - at the rate of the progress bar.
    private var runSettled: Bool {
        localAnalysis?.settledRuns.contains(analysisId) ?? false
    }

    var body: some View {
        let entry = rally.localVideos.get(id: analysisId)
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if panels.count > 1 {
                    ShuttlPillTabs(
                        labels: panels.map(\.label),
                        selectedIndex: panels.firstIndex(of: panel) ?? 0,
                        onSelect: { chosen = panels[$0] },
                        accessibilityLabel: "Analysis panel",
                        // Secondary: this row is Analysis's own, under the
                        // page's section row, and must not read as its peer.
                        secondary: true
                    )
                    // No top padding, here or on the heading below: the gap
                    // under the page's section row is the stack's, below.
                    .padding(.horizontal, ShuttlGutter.page)
                    .padding(.bottom, 16)
                } else {
                    // Styled as a small uppercase section label, not as a title:
                    // the label sits under the page's own section row, and two
                    // treatments of the same thing there reads as two different
                    // kinds of heading.
                    Text("HEATMAP")
                        .shuttlType(ShuttlType.labelSmall)
                        .foregroundStyle(Shuttl.textSecondary)
                        .padding(.horizontal, ShuttlGutter.page)
                        .padding(.bottom, 12)
                }
                switch panel {
                case .heatmap:
                    heatmap
                case .skeleton:
                    if let localAnalysis {
                        SkeletonPanel(
                            entryId: analysisId,
                            videoRelativePath: entry?.uri,
                            runner: localAnalysis,
                            prefs: rally.playbackPrefs,
                            racketArmPrefs: rally.racketArmPrefs
                        )
                    }
                case .base:
                    BasePositionPanel(source: source, windows: windows)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            // The page's `sectionGap`, inside the scroll rather than above it
            // as the other sections take it: a pill row at the scroll view's
            // top edge lost the upper half of its 44pt target, since nothing
            // outside a scroll view's bounds takes a touch.
            .padding(.top, MatchPageLayout.sectionGap)
        }
        .background(Shuttl.bg)
        // Both keyed on the id they read: the page's `analysisId` moves from
        // the entry to the heatmap's own id when a run lands while it is open,
        // and a task that kept the old one would go on drawing the old match.
        .task(id: analysisId) { await reload() }
        // The clip cache, watched rather than read once: it refreshes
        // asynchronously after a cloud run, and the Base panel's windows come
        // out of it. Reading it only on open left the panel missing for the
        // seconds between the section appearing and the clips landing.
        .task(id: analysisId) {
            for await latest in rally.clips.observeClips() {
                cloudClips = latest
                await reload()
            }
        }
        .onChange(of: runSettled) { _, _ in Task { await reload() } }
    }

    /// What the three stored reads produced, carried back from the task that
    /// did them.
    private struct Stored {
        let track: PlayerTrackStore.Stored?
        let hasSkeleton: Bool
        let clips: [PlayerTrackStore.Clip]
    }

    /// Re-reads what this match has. One parse of the track and one header read,
    /// on opening and whenever a run settles - never per redraw.
    ///
    /// The disk is read even when a run is in memory, because that run may be a
    /// pose-less one whose empty track has to fall through to a stored one; see
    /// `heatmapSource`.
    private func reload() async {
        let done: LocalAnalysisState.Done? = {
            if case .done(let done) = localAnalysis?.state(for: analysisId) ?? .idle { return done }
            return nil
        }()
        // The three disk reads off the main actor. `storedTrack`, `storedClips`
        // and `hasStoredSkeleton` are `nonisolated` precisely so they can run
        // there; calling them from a main-actor task put the megabyte track
        // parse, the clips sidecar and the skeleton header back on the main
        // thread on every open and every time a run settled behind this section.
        let runner = localAnalysis
        let id = analysisId
        let stored = await Task.detached(priority: .userInitiated) { () -> Stored in
            Stored(
                track: runner?.storedTrack(entryId: id),
                hasSkeleton: runner?.hasStoredSkeleton(entryId: id) ?? false,
                clips: runner?.storedClips(entryId: id) ?? []
            )
        }.value

        source = heatmapSource(done: done, stored: stored.track)
        hasSkeleton = stored.hasSkeleton
        // One source for the whole section. A median taken over one run's rally
        // boundaries and plotted from another run's track is two answers on one
        // court, so whichever run `heatmapSource` drew also supplies the windows.
        windows = CloudRallyWindowsKt.rallyWindowsFor(
            preferCloud: stored.track?.source == .cloud,
            cloud: CloudRallyWindowsKt.cloudRallyWindows(clips: cloudClips, videoId: id),
            device: rallyWindows(done: done?.clips ?? [], stored: stored.clips)
        )
    }

    /// One match's court heatmap, or an honest line saying why there is none.
    ///
    /// In memory if the run is still loaded, from disk otherwise - `reload`
    /// resolves which. A pose run costs minutes to half an hour, so losing its
    /// result to a termination and asking for another one is not an option.
    @ViewBuilder
    private var heatmap: some View {
        if let source {
            let shown = source.tracks.first { $0.side == side } ?? source.tracks[0]
            VStack(spacing: 0) {
                if source.tracks.count > 1 {
                    SideToggle(
                        sides: source.tracks.map(\.side),
                        selected: shown.side,
                        onSelect: { side = $0 }
                    )
                    // The court's gutter, as on Base and Skeleton: at 16pt this
                    // row stood wider than the section and panel rows above it
                    // and jumped sideways on every switch to Base.
                    .padding(.horizontal, ShuttlGutter.page)
                    .padding(.vertical, 8)
                }
                CourtHeatmapView(track: shown.track, fps: source.fps)
            }
        } else {
            // A run's state lives in memory, so it is gone after a termination.
            // Said plainly rather than drawing an empty court, which would read
            // as a player who never moved.
            PanelMessage(text: "This analysis is no longer loaded. Run it again to see the heatmap.")
        }
    }
}

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

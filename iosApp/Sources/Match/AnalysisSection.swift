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
                    .padding(.horizontal, ShuttlGutter.page)
                    .padding(.top, 8)
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
                        .padding(.vertical, 12)
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
        }
        .background(Shuttl.bg)
        .task { await reload() }
        // The clip cache, watched rather than read once: it refreshes
        // asynchronously after a cloud run, and the Base panel's windows come
        // out of it. Reading it only on open left the panel missing for the
        // seconds between the section appearing and the clips landing.
        .task {
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

import Shared
import SwiftUI

/// Feeds a `ClipListModel` what the on-device runner is doing and what earlier
/// runs left on disk, so `model.matchList` never goes stale.
///
/// Applied exactly once, on `HomeView`'s own `NavigationStack` - the common
/// parent of `MatchesList` and `AnalyticsListView`, which stays mounted for
/// the app's whole life regardless of which destination is pushed on top of
/// it. It used to live on `MatchesList` alone, then on both `MatchesList` and
/// `AnalyticsListView`: the first left the model looking no different when
/// Analytics pushed on top, because the drawer underneath it never actually
/// stops running its own modifiers in a `NavigationStack` - so applying it a
/// second time on `AnalyticsListView` did not fix a staleness problem, it
/// just ran the same two probes twice at once, redundantly. One instance, on
/// the one view that is always there, is both correct and cheaper. The model
/// still does not bridge `LocalAnalysisRunner`'s `@Observable` properties into
/// an async sequence of its own - see `ClipListModelTests`' loop-lifecycle
/// tests for why that would be risky.
extension View {
    func feedsMatchList(model: ClipListModel, localAnalysis: LocalAnalysisRunner?) -> some View {
        modifier(MatchListFeed(model: model, localAnalysis: localAnalysis))
    }
}

private struct MatchListFeed: ViewModifier {
    let model: ClipListModel
    let localAnalysis: LocalAnalysisRunner?

    func body(content: Content) -> some View {
        content
            // `deviceRuns`, never `states`: the runner writes `states` once per
            // analysed frame, and the model rebuilds `matchList` on every
            // change it sees.
            .onChange(of: localAnalysis?.deviceRuns ?? [:], initial: true) { _, runs in
                model.updateDeviceRuns(runs)
            }
            // Re-read what earlier runs left on disk whenever that could have
            // changed: an entry arriving or leaving, a stage moving (a cloud
            // run installs its track before it writes ANALYZED), a cloud video
            // appearing, or a device run settling - a track and a set of clips
            // appear on disk without anything else moving. The key is what
            // changes, not the ids alone: an ids-only key never showed a
            // finished run's results.
            .task(id: storedResultsKey) { await refreshStoredResults(storedResultsKey) }
    }

    /// Everything whose change can mean different files on disk.
    private var storedResultsKey: StoredResultsKey {
        StoredResultsKey(
            entries: model.localEntries.map { "\($0.id):\($0.stage)" },
            videoIds: Set(model.clips.map(\.videoId)),
            settled: localAnalysis?.settledRuns ?? []
        )
    }

    /// Re-reads what each row's earlier runs left on disk.
    ///
    /// Not cheap: `storedClipCounts` parses a sidecar and stats a file per clip
    /// for every entry, and the track and skeleton probes are one header read
    /// per id. Off the main actor for that reason, and every accessor is
    /// `nonisolated` so it can be. Tracks and skeletons are probed by entry id
    /// and by every cloud video id, since a cloud run keys them by the video;
    /// clip counts by entry id only, which is all a device run keys them by.
    private func refreshStoredResults(_ key: StoredResultsKey) async {
        guard let localAnalysis else {
            model.updateStoredResults(tracks: [], skeletons: [], clipCounts: [:])
            return
        }
        let entryIds = model.localEntries.map(\.id)
        let ids = Array(Set(entryIds).union(key.videoIds))
        let runner = localAnalysis
        let read = await Task.detached(priority: .userInitiated) {
            (
                tracks: runner.storedTrackIds(among: ids),
                skeletons: runner.storedSkeletonIds(among: ids),
                counts: runner.storedClipCounts(among: entryIds)
            )
        }.value
        guard !Task.isCancelled else { return }
        model.updateStoredResults(tracks: read.tracks, skeletons: read.skeletons, clipCounts: read.counts)
    }
}

private struct StoredResultsKey: Hashable {
    let entries: [String]
    let videoIds: Set<String>
    let settled: Set<String>
}

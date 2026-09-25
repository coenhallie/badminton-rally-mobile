import Shared
import SwiftUI

/// Feeds a `ClipListModel` what the on-device runner is doing and what earlier
/// runs left on disk, so `model.matchList` never goes stale while either
/// consumer is on screen.
///
/// Both `MatchesList` and `AnalyticsListView` apply this, rather than each
/// keeping its own copy of the same two probes. Before this existed only the
/// drawer fed the model (`MatchesList`'s own `.onChange`/`.task(id:)`), so a
/// device run that started or settled while Analytics was the screen on top -
/// and the drawer was not - left Analytics reading a `matchList` the drawer
/// had last delivered. Applying the same modifier to both means whichever of
/// the two is actually on screen keeps the model current, without the model
/// itself having to bridge `LocalAnalysisRunner`'s `@Observable` properties
/// into an async sequence of its own.
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

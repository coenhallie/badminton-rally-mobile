import Foundation
import Shared

/// One match, folded. Everything is derived from the store directly, so a point
/// scored elsewhere or a video finishing its pipeline redraws this page without a
/// refresh once `start()`'s loop picks up the change.
///
/// The Swift half of `MatchViewModel`.
@Observable @MainActor
final class MatchModel {
    /// Nil for a video-first or shared match - the page it opens on has no score
    /// log to look up.
    let scoreLogId: String?

    /// Nil for a video-first or shared match, and for one deleted while open.
    private(set) var log: ScoreLog? = nil
    private(set) var match: MatchState? = nil
    private(set) var card: ScoreMatchCard? = nil
    private(set) var tally: ScoreTagSummary = ScoreTagSummary.companion.EMPTY
    /// A finished match with no video, and no video already picked for it. The
    /// one condition "Add video" is offered on.
    private(set) var canAddVideo: Bool = false
    /// What this match's video is doing right now, or nil when there is nothing
    /// to say - same derivation and precedence as the match list's own row, see
    /// `AttachStatusKt.scoreLogAttachStatus`.
    private(set) var attach: AttachStatus? = nil

    private let scoreLogs: ScoreLogsRepository
    private let localVideos: LocalVideoRepository
    private let analyze: AnalyzeCoordinator
    private let clips: ClipsRepository

    private var localEntries: [LocalVideoEntry]
    private var progressByEntryId: [String: AnalyzeProgress] = [:]
    private var allClips: [RallyClip] = []

    init(
        scoreLogs: ScoreLogsRepository,
        localVideos: LocalVideoRepository,
        analyze: AnalyzeCoordinator,
        clips: ClipsRepository,
        scoreLogId: String?
    ) {
        self.scoreLogs = scoreLogs
        self.localVideos = localVideos
        self.analyze = analyze
        self.clips = clips
        self.scoreLogId = scoreLogId
        self.localEntries = localVideos.entries.value
        readStore()
    }

    convenience init(rally: RallyApp, analyze: AnalyzeCoordinator, scoreLogId: String?) {
        self.init(
            scoreLogs: rally.scoreLogs, localVideos: rally.localVideos, analyze: analyze,
            clips: rally.clips, scoreLogId: scoreLogId
        )
    }

    /// Never returns: `MatchView`'s `.task` runs this for as long as the page is on
    /// screen, so a point scored elsewhere or a video finishing its pipeline
    /// redraws this page without a manual refresh.
    func start() async {
        // Each its own task, matching ClipListModel.start(): the scoreLogs loop at
        // the bottom never returns, so anything sequenced after it never would.
        Task {
            for await entries in localVideos.entries {
                localEntries = entries
                readStore()
            }
        }
        Task {
            for await map in analyze.progress {
                progressByEntryId = map
                readStore()
            }
        }
        Task {
            for await latest in clips.observeClips() {
                allClips = latest
                readStore()
            }
        }
        for await _ in scoreLogs.logs {
            readStore()
        }
    }

    /// A synchronous re-read for a caller that mutates the store directly rather
    /// than going through `start()`'s loop. Mirrors `ScoringModel.reload()`, kept
    /// for the same reason: not every caller can afford to wait on the flow.
    func reload() { readStore() }

    private func readStore() {
        guard let scoreLogId, let found = scoreLogs.get(id: scoreLogId) else {
            log = nil
            match = nil
            card = nil
            tally = ScoreTagSummary.companion.EMPTY
            canAddVideo = false
            attach = nil
            return
        }
        log = found
        let state = found.state()
        match = state
        card = ScoreMatchCardKt.buildScoreMatchCard(log: found)
        tally = ScoreTagSummaryKt.buildScoreTagSummary(state: state)
        // An entry can exist for a while before the pipeline gives the match a
        // videoId (LOCAL, UPLOADING, PROCESSING); gating on videoId alone would
        // let "Add video" reopen the picker mid-attach.
        let hasPendingEntry = localEntries.contains { $0.scoreLogId == scoreLogId }
        // isPlayable(), not `status != .live`: the board's Done button navigates
        // without finishing, deliberately, so that a match ended on a mis-tap can
        // still be undone - which leaves a won match sitting at .live. This is the
        // predicate every surface should ask, rather than either half of it.
        canAddVideo = !found.isPlayable() && found.videoId == nil && !hasPendingEntry
        let clipCount = allClips.filter { $0.videoId == found.videoId }.count
        attach = AttachStatusKt.scoreLogAttachStatus(
            log: found, entries: localEntries, progress: progressByEntryId, clipCount: Int32(clipCount)
        )
    }
}

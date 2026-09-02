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
    /// This match has a video and nothing is in flight for it: the one condition
    /// "Change video" and "Remove video" are offered on, see
    /// `MatchVideoRemovalKt.scoreLogCanRemoveVideo`. Mutually exclusive with
    /// `canAddVideo`.
    private(set) var canRemoveVideo: Bool = false
    /// Whether a videos row exists, which decides what the confirm says is about
    /// to be lost - an entry that never reached CREATE_ROW has no rallies and no
    /// notes to lose.
    private(set) var hasServerVideo: Bool = false
    /// A failed removal, for the page's error banner. Nothing else on this page
    /// can fail: everything else it shows is derived from stores it only reads.
    var error: String? = nil

    private let scoreLogs: ScoreLogsRepository
    private let localVideos: LocalVideoRepository
    private let analyze: AnalyzeCoordinator
    private let clips: ClipsRepository
    private let videos: VideosRepository
    private let localAnnotations: LocalAnnotationsRepository

    private var localEntries: [LocalVideoEntry]
    private var progressByEntryId: [String: AnalyzeProgress] = [:]
    private var allClips: [RallyClip] = []

    init(
        scoreLogs: ScoreLogsRepository,
        localVideos: LocalVideoRepository,
        analyze: AnalyzeCoordinator,
        clips: ClipsRepository,
        videos: VideosRepository,
        localAnnotations: LocalAnnotationsRepository,
        scoreLogId: String?
    ) {
        self.scoreLogs = scoreLogs
        self.localVideos = localVideos
        self.analyze = analyze
        self.clips = clips
        self.videos = videos
        self.localAnnotations = localAnnotations
        self.scoreLogId = scoreLogId
        self.localEntries = localVideos.entries.value
        readStore()
    }

    convenience init(rally: RallyApp, analyze: AnalyzeCoordinator, scoreLogId: String?) {
        self.init(
            scoreLogs: rally.scoreLogs, localVideos: rally.localVideos, analyze: analyze,
            clips: rally.clips, videos: rally.videos, localAnnotations: rally.localAnnotations,
            scoreLogId: scoreLogId
        )
    }

    /// Takes the video off this match and leaves the match: the ordering, and the
    /// message on failure, are `removeMatchVideoOrMessage`'s. Returns whether the
    /// video is gone, which is what makes "Change video" one gesture rather than
    /// two - once removal lands this match has no video in either sense, so the
    /// caller can go straight to the picker the page already owns. Mirrors
    /// Android's `MatchViewModel.removeVideo`.
    @discardableResult
    func removeVideo() async -> Bool {
        guard let scoreLogId else { return false }
        // A previous failure must not sit over a fresh attempt: the banner has no
        // timeout of its own, matching every other ErrorBanner in the app.
        error = nil
        // do/catch, not `try?`: `try?` would flatten a thrown error and a nil
        // (success) into the same case, and this return value gates a picker.
        do {
            let message = try await MatchVideoRemovalKt.removeMatchVideoOrMessage(
                scoreLogId: scoreLogId,
                scoreLogs: scoreLogs,
                videos: videos,
                clips: clips,
                localVideos: localVideos,
                localAnnotations: localAnnotations
            )
            // The repository directly, not `start()`'s cached copy: that loop may
            // not have delivered the shorter list yet, and this page must not go
            // on offering a gesture for a video it has just removed.
            localEntries = localVideos.entries.value
            readStore()
            if let message {
                error = message
                return false
            }
            return true
        } catch {
            self.error = MatchVideoRemovalKt.MATCH_VIDEO_REMOVE_FAILED_MESSAGE
            return false
        }
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
            canRemoveVideo = false
            hasServerVideo = false
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
        canRemoveVideo = MatchVideoRemovalKt.scoreLogCanRemoveVideo(log: found, entries: localEntries)
        hasServerVideo = found.videoId != nil
    }
}

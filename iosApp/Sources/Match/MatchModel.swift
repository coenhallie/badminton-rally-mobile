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
    /// A finished match with no video. The one condition "Add video" is offered on.
    private(set) var canAddVideo: Bool = false

    private let scoreLogs: ScoreLogsRepository

    init(scoreLogs: ScoreLogsRepository, scoreLogId: String?) {
        self.scoreLogs = scoreLogs
        self.scoreLogId = scoreLogId
        readStore()
    }

    convenience init(rally: RallyApp, scoreLogId: String?) {
        self.init(scoreLogs: rally.scoreLogs, scoreLogId: scoreLogId)
    }

    /// Never returns: `MatchView`'s `.task` runs this for as long as the page is on
    /// screen, so a point scored elsewhere or a video finishing its pipeline
    /// redraws this page without a manual refresh.
    func start() async {
        for await _ in scoreLogs.logs {
            readStore()
        }
    }

    /// Re-reads the store without waiting for its flow. Tests use it where the app
    /// relies on the loop in `start`.
    func reload() { readStore() }

    private func readStore() {
        guard let scoreLogId, let found = scoreLogs.get(id: scoreLogId) else {
            log = nil
            match = nil
            card = nil
            tally = ScoreTagSummary.companion.EMPTY
            canAddVideo = false
            return
        }
        log = found
        let state = found.state()
        match = state
        card = ScoreMatchCardKt.buildScoreMatchCard(log: found)
        tally = ScoreTagSummaryKt.buildScoreTagSummary(state: state)
        // isPlayable(), not `status != .live`: the board's Done button navigates
        // without finishing, deliberately, so that a match ended on a mis-tap can
        // still be undone - which leaves a won match sitting at .live. This is the
        // predicate every surface should ask, rather than either half of it.
        canAddVideo = !found.isPlayable() && found.videoId == nil
    }
}

import Foundation
import Shared

/// The board a coach taps, minus the pixels.
///
/// The Swift half of `ScoringViewModel`. Every mutation goes through the shared
/// `MatchScorer` rather than touching `ScoreLogsRepository` directly, which is the
/// whole reason that class exists: the two surfaces cannot come to disagree about
/// what "undo" or "reset game" means.
@Observable @MainActor
final class ScoringModel {
    let scoreLogId: String

    /// Nil when the match is not on this device - removed from the list while this
    /// screen was open, or a cold start before the cache has been read.
    private(set) var log: ScoreLog? = nil
    private(set) var match: MatchState? = nil
    private(set) var labels: [AnnotationLabel] = []

    /// Whether the account has any labels at all, board-scoped or not. Lets the
    /// board tell "you have not made any labels" apart from "none of yours are
    /// on the board", which are different problems with different fixes.
    private(set) var hasAnyLabels: Bool = false

    /// The point the tag row is pointing at. State rather than a dialog: that is
    /// what lets one tap score and a second tap tag without a modal ever appearing
    /// between the coach and the next rally.
    private(set) var pendingTagOrdinal: Int? = nil

    var canScore: Bool { match?.isOver == false }
    var canUndo: Bool { !(log?.events.isEmpty ?? true) }

    private let scoreLogs: ScoreLogsRepository
    private let scorer: MatchScorer
    /// Nil in tests. Swift cannot cheaply stand in for a Kotlin interface, so the
    /// tests hand over a fixed palette instead of a repository to observe - which
    /// is also what the surface actually needs, since it reads the board-scoped
    /// subset of the cache and never refreshes on entry.
    private let labelsRepository: AnnotationLabelsRepository?

    init(
        scoreLogs: ScoreLogsRepository,
        labelsRepository: AnnotationLabelsRepository? = nil,
        scoreboardLabels: [AnnotationLabel] = [],
        scoreLogId: String
    ) {
        self.scoreLogs = scoreLogs
        self.labelsRepository = labelsRepository
        self.scoreLogId = scoreLogId
        self.scorer = MatchScorer(repo: scoreLogs, scoreLogId: scoreLogId)
        self.labels = labelsRepository?.scoreboardLabels.value ?? scoreboardLabels
        self.hasAnyLabels = !(labelsRepository?.labels.value.isEmpty ?? scoreboardLabels.isEmpty)
        readStore()
    }

    convenience init(rally: RallyApp, scoreLogId: String) {
        self.init(scoreLogs: rally.scoreLogs, labelsRepository: rally.labels, scoreLogId: scoreLogId)
    }

    func start() async {
        // Its own task: the store loop below never returns, so anything after it
        // would never run. The palette is read from the cache and never refreshed
        // on entry - a sports hall has no signal.
        if let labelsRepository {
            Task {
                for await palette in labelsRepository.scoreboardLabels {
                    labels = palette
                }
            }
            Task {
                for await all in labelsRepository.labels {
                    hasAnyLabels = !all.isEmpty
                }
            }
        }
        for await _ in scoreLogs.logs {
            readStore()
        }
    }

    /// Re-reads the store without waiting for its flow. The tests use it where the
    /// app relies on the loop in `start`.
    func reload() { readStore() }

    /// One tap. The tag row follows to the rally just played.
    func score(_ side: Side) {
        scorer.score(side: side)
        readStore()
        let count = match?.pointCount ?? 0
        pendingTagOrdinal = count > 0 ? Int(count) - 1 : nil
    }

    /// Adds `label` to the rally, or takes it off again if it is already there. The
    /// row stays open: a rally can be a good shot and a forced error at once, and
    /// re-opening the row to say so would cost the coach the next point.
    func toggleTag(ordinal: Int, label: AnnotationLabel) {
        guard let point = point(at: ordinal) else { return }
        let existing = point.tags
        let next: [PointTag] = existing.contains { $0.labelName == label.name }
            ? existing.filter { $0.labelName != label.name }
            : existing + [PointTag(labelName: label.name, labelColor: label.colorKey)]
        scorer.tagPoint(ordinal: Int32(ordinal), tags: next, comment: point.comment)
        readStore()
    }

    /// The longer thought, written between rallies or at the interval. Committed
    /// once rather than per keystroke: `tagPoint` appends and undo drops the last
    /// entry, so typing straight through would make undo take a note back a letter
    /// at a time instead of taking back the rally.
    func setComment(ordinal: Int, text: String) {
        guard let point = point(at: ordinal) else { return }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        scorer.tagPoint(ordinal: Int32(ordinal), tags: point.tags, comment: trimmed.isEmpty ? nil : trimmed)
        readStore()
    }

    func undo() {
        scorer.undo()
        readStore()
    }

    func resetCurrentGame() {
        scorer.resetCurrentGame()
        pendingTagOrdinal = nil
        readStore()
    }

    func finish() {
        scorer.finish()
        pendingTagOrdinal = nil
        readStore()
    }

    /// The rally the tag row is on, or nil when there is not one.
    var pendingPoint: ScoredPoint? {
        pendingTagOrdinal.flatMap { point(at: $0) }
    }

    private func point(at ordinal: Int) -> ScoredPoint? {
        guard let points = match?.points, ordinal >= 0, ordinal < points.count else { return nil }
        return points[ordinal]
    }

    /// Re-folds from the store. Called after every mutation as well as from the
    /// flow, because a tap and the tag that follows it can both land before the
    /// flow has emitted once.
    private func readStore() {
        let found = scoreLogs.get(id: scoreLogId)
        log = found
        match = found?.state()
        // A row still pointing at a rally that undo took back would put the next
        // label on a point that no longer exists.
        if let ordinal = pendingTagOrdinal, ordinal >= (match?.points.count ?? 0) {
            pendingTagOrdinal = nil
        }
    }
}

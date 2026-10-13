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

    /// The open moment composer. Only ever open while `target` is moments.
    private(set) var momentDraft: MomentDraft? = nil

    /// The strip's last notice, until its action is taken or it times out.
    private var lastNotice: BoardNotice? = nil

    var canScore: Bool { match?.isOver == false }
    var canUndo: Bool { (match?.points.count ?? 0) > 0 }
    /// Whether the board offers the score editor. See `MatchScorer.canEditScore`.
    /// Reads `log` first so the view is re-asked when it changes.
    var canEditScore: Bool { log != nil && scorer.canEditScore() }

    /// What a chip tap does: tag `pendingTagOrdinal`'s rally, or log a moment.
    /// Nil with no match. See `boardTarget`.
    var target: BoardTarget? {
        guard let match else { return nil }
        return BoardPresentationKt.boardTarget(
            match: match,
            pendingOrdinal: pendingTagOrdinal.map { KotlinInt(int: Int32($0)) }
        )
    }

    /// The line beside Edit score. See `boardCaption`.
    var caption: String {
        guard let match, let target else { return "" }
        return BoardPresentationKt.boardCaption(
            match: match, target: target, hasBoardLabels: !labels.isEmpty,
            draft: target is BoardTarget.Moments ? momentDraft : nil
        )
    }

    /// Whether the menu offers "Remove last moment".
    var canRemoveMoment: Bool { !(match?.moments.isEmpty ?? true) }

    /// The strip's notice while its action can still do what it says. Read against
    /// the log rather than cleared by each caller, so a change from anywhere - a
    /// rally, a tag, a sync - retires it with it. Reads `log` first so the view is
    /// re-asked when it changes.
    var notice: BoardNotice? {
        guard log != nil, let lastNotice, scorer.isLive(notice: lastNotice) else { return nil }
        return lastNotice
    }
    var noticeMessage: String? { notice.flatMap { n in log?.noticeMessage(notice: n) } }
    var noticeAction: String? { notice?.actionLabel }

    /// True until the first point: the window in which the board can hand the
    /// opening serve across. Reads `log` first so the view is re-asked when it changes.
    var canSwitchFirstServer: Bool { log != nil && scorer.canSwitchFirstServer() }

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
        // Opens on the last rally, so a board reopened mid-match tags it, as the
        // board would have the moment it was scored.
        pendingTagOrdinal = lastOrdinal
    }

    convenience init(rally: RallyApp, scoreLogId: String) {
        self.init(scoreLogs: rally.scoreLogs, labelsRepository: rally.labels, scoreLogId: scoreLogId)
    }

    /// Runs for as long as the board is on screen, as structured children of
    /// `ScoringView`'s `.task` - the same shape as `BackgroundWorkModel.observe`.
    /// The two palette loops used to be unstructured `Task {}`s, which
    /// cancellation cannot reach, so every run of that `.task` left two behind.
    ///
    /// The palette is read from the cache and never refreshed on entry - a
    /// sports hall has no signal.
    func start() async {
        await withTaskGroup(of: Void.self) { group in
            if let labelsRepository {
                group.addTask { @MainActor [weak self] in
                    guard let self else { return }
                    for await palette in labelsRepository.scoreboardLabels {
                        self.labels = palette
                    }
                }
                group.addTask { @MainActor [weak self] in
                    guard let self else { return }
                    for await all in labelsRepository.labels {
                        self.hasAnyLabels = !all.isEmpty
                    }
                }
            }
            group.addTask { @MainActor [weak self] in
                guard let self else { return }
                for await _ in self.scoreLogs.logs {
                    self.readStore()
                }
            }
        }
    }

    /// Re-reads the store without waiting for its flow. The tests use it where the
    /// app relies on the loop in `start`.
    func reload() { readStore() }

    /// One tap. The tag row follows to the rally just played.
    func score(_ side: Side) {
        commitMomentDraft()
        scorer.score(side: side)
        lastNotice = nil
        readStore()
        pendingTagOrdinal = lastOrdinal
    }

    /// A chip tap while the row targets moments: a check on the composer's moment
    /// while it is open, otherwise a moment of its own.
    func tapMomentLabel(_ label: AnnotationLabel) {
        let tag = PointTag(labelName: label.name, labelColor: label.colorKey)
        if let draft = momentDraft {
            momentDraft = draft.toggle(tag: tag)
            return
        }
        guard let moment = scorer.logMoment(tags: [tag]) else { return }
        lastNotice = BoardNotice.MomentAdded(moment: moment)
        readStore()
    }

    /// Only while the row targets moments, so a draft never waits behind a rally to resurface later.
    func openMomentDraft() {
        guard target is BoardTarget.Moments, momentDraft == nil else { return }
        momentDraft = scorer.openMomentDraft()
    }

    /// Kept here so a score tap can save the draft; the field reads its own copy.
    func setMomentDraftText(_ text: String) {
        momentDraft = momentDraft?.withText(text: text)
    }

    func commitMomentDraft() {
        guard let draft = momentDraft else { return }
        momentDraft = nil
        if let moment = scorer.commitMomentDraft(draft: draft) {
            lastNotice = BoardNotice.MomentAdded(moment: moment)
        }
        readStore()
    }

    func removeLastMoment() {
        guard let last = scorer.lastMoment(), let removed = scorer.removeMoment(id: last.id) else { return }
        lastNotice = BoardNotice.MomentRemoved(moment: removed)
        readStore()
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
    /// once rather than per keystroke: `tagPoint` appends, so typing straight
    /// through would add one log entry per character to a log that is stored and
    /// synced whole.
    func setComment(ordinal: Int, text: String) {
        guard let point = point(at: ordinal) else { return }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        scorer.tagPoint(ordinal: Int32(ordinal), tags: point.tags, comment: trimmed.isEmpty ? nil : trimmed)
        readStore()
    }

    func updateDetails(title: String, format: MatchFormat?, homePlayers: [String], awayPlayers: [String]) {
        scorer.updateDetails(title: title, format: format, homePlayers: homePlayers, awayPlayers: awayPlayers)
        readStore()
    }

    func switchFirstServer() {
        scorer.switchFirstServer()
        readStore()
    }

    /// Takes back the last rally. The tag row goes back to the rally before it, so
    /// the board looks exactly as it did the moment that rally was scored.
    func undo() {
        commitMomentDraft()
        guard let taken = scorer.undo() else { return }
        lastNotice = BoardNotice.RallyUndone(undone: taken)
        readStore()
        pendingTagOrdinal = lastOrdinal
    }

    /// The strip's action: Redo after an undo, Undo after a moment was added or removed.
    func reverseNotice() {
        guard let notice = lastNotice else { return }
        lastNotice = nil
        scorer.reverse(notice: notice)
        readStore()
        // Redo moves the row onto the rally it put back, so an open composer is
        // saved as it would be by a score tap. Saved after the redo, not before:
        // saving changes the log, which would retire the Redo. Undoing a moment
        // leaves the row on moments, and the composer open.
        if notice is BoardNotice.RallyUndone {
            commitMomentDraft()
            pendingTagOrdinal = lastOrdinal
        }
    }

    /// The notice timed out. What it reported stands.
    func dismissNotice() {
        lastNotice = nil
    }

    /// The score editor's save. The tag row follows to the last rally, as it
    /// does after a tap, so a rally just added can be tagged straight away.
    func editScore(home: Int, away: Int, nextServer: Side?) {
        commitMomentDraft()
        guard scorer.editScore(home: Int32(home), away: Int32(away), nextServer: nextServer) else { return }
        lastNotice = nil
        readStore()
        pendingTagOrdinal = lastOrdinal
    }

    func resetCurrentGame() {
        commitMomentDraft()
        scorer.resetCurrentGame()
        lastNotice = nil
        pendingTagOrdinal = nil
        readStore()
    }

    func finish() {
        commitMomentDraft()
        scorer.finish()
        lastNotice = nil
        pendingTagOrdinal = nil
        readStore()
    }

    /// The rally the tag row is on, or nil when there is not one.
    var pendingPoint: ScoredPoint? {
        pendingTagOrdinal.flatMap { point(at: $0) }
    }

    private var lastOrdinal: Int? {
        let count = match?.points.count ?? 0
        return count > 0 ? count - 1 : nil
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

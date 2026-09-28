import Shared

enum LocalVideoStatus {
    /// How a cloud run reads on this row. Forwards the shared rule rather than
    /// porting it: this used to be a hand-copied version of Android's own
    /// mapping, and the copy that mattered was the third one - Android's
    /// since-deleted Analytics list wrote its own and drifted to "Uploading"
    /// with no percentage while this said "Uploading 42%…" for the same run.
    static func text(stage: AnalyzeStage, progress: AnalyzeProgress?) -> String? {
        LocalVideoEntryKt.cloudAnalysisStatus(stage: stage, progress: progress)
    }

    /// What a paused device run says. `deviceRun(for:)` below hands this to the
    /// shared builder, which is what actually reaches a row's status line now
    /// (`MatchStatusLine`, via `MatchListItem.statusText`), so this is the one
    /// place that wording lives rather than a second copy at a call site that
    /// used to format its own row directly.
    static let pausedText = "Paused - keep Shuttl open"

    /// A device run as the shared match list sees it, or nil when there is no
    /// run to speak of: idle, or done, in which case the files it left say
    /// what it produced.
    ///
    /// The one reduction from this platform's run state to the list's: the
    /// builder turns FAILED into the same "Analysis failed: ..." line and
    /// PAUSED into `pausedText`, so a row's status line and this file cannot
    /// word a run differently. A paused run keeps the phase it was frozen in,
    /// although the list shows only the message for it.
    static func deviceRun(for state: LocalAnalysisState) -> DeviceRun? {
        switch state {
        case .idle, .done:
            return nil
        case .preparing:
            return DeviceRun(kind: .running, phase: .preparing, message: nil)
        case .analysing:
            return DeviceRun(kind: .running, phase: .analysing, message: nil)
        case .cutting:
            return DeviceRun(kind: .running, phase: .cutting, message: nil)
        case .paused(let interrupted):
            let phase = deviceRun(for: interrupted)?.phase ?? .analysing
            return DeviceRun(kind: .paused, phase: phase, message: pausedText)
        case .failed(let message):
            return DeviceRun(kind: .failed, phase: .analysing, message: message)
        }
    }

    /// Title and description ride along on the videos INSERT and the database
    /// grants no UPDATE on either column, so editing stops the moment the entry
    /// leaves LOCAL. Forwards the shared rule so both platforms match.
    static func canEditDetails(stage: AnalyzeStage) -> Bool {
        LocalVideoEntryKt.canEditLocalVideoDetails(stage: stage)
    }

    /// Row spinner: only while the pipeline is actively working. Settled
    /// stages (ANALYZED in particular) must not spin forever.
    static func isRunning(stage: AnalyzeStage) -> Bool {
        LocalVideoEntryKt.isAnalysisRunning(stage: stage)
    }
}

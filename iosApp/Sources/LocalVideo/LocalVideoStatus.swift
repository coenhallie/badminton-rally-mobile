import Shared

enum LocalVideoStatus {
    /// How a cloud run reads on this row. Forwards the shared rule rather than
    /// porting it: this used to be a hand-copied version of Android's own
    /// mapping, and the copy that mattered was the third one - Android's
    /// Analytics list wrote its own and drifted to "Uploading" with no
    /// percentage while this said "Uploading 42%…" for the same run.
    static func text(stage: AnalyzeStage, progress: AnalyzeProgress?) -> String? {
        LocalVideoEntryKt.cloudAnalysisStatus(stage: stage, progress: progress)
    }

    static func canAnalyze(stage: AnalyzeStage) -> Bool {
        stage == .local || stage == .failed
    }

    /// Removal deletes the entry and its file; blocked while the pipeline is
    /// uploading/processing. Forwards the shared rule so both platforms match.
    static func canRemove(stage: AnalyzeStage) -> Bool {
        LocalVideoEntryKt.canRemoveLocalVideo(stage: stage)
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

    /// "Re-analyze" once an attempt has failed (the video keeps its saved court
    /// points and resumes from the failed step); "Analyze" for a fresh video.
    static func analyzeButtonLabel(stage: AnalyzeStage) -> String {
        stage == .failed ? "Re-analyze" : "Analyze"
    }
}

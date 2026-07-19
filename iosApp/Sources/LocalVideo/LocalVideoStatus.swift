import Shared

/// Verbatim port of Android LocalVideoListViewModel.toRow's status mapping.
enum LocalVideoStatus {
    static func text(stage: AnalyzeStage, uploadProgress: Float?, pipelineProgress: Float?) -> String? {
        switch stage {
        case .local, .failed:
            return nil
        case .uploading:
            if let p = uploadProgress { return "Uploading \(Int(p * 100))%…" }
            return "Uploading…"
        case .processing:
            if let p = pipelineProgress { return "Analyzing \(Int(p * 100))%…" }
            return "Analyzing…"
        case .analyzed:
            return "Analyzed"
        default:
            return nil
        }
    }

    static func canAnalyze(stage: AnalyzeStage) -> Bool {
        stage == .local || stage == .failed
    }

    /// Removal deletes the entry and its file; blocked while the pipeline is
    /// uploading/processing. Forwards the shared rule so both platforms match.
    static func canRemove(stage: AnalyzeStage) -> Bool {
        LocalVideoEntryKt.canRemoveLocalVideo(stage: stage)
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

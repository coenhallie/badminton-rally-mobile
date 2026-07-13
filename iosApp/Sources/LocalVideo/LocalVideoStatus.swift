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
}

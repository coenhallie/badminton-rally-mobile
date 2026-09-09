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

    /// Whether this video's Analyze button should be live.
    ///
    /// Both pipelines, because either can be busy with it and only one of them
    /// moves the stage. A device run leaves the stage on LOCAL for its whole
    /// length, so a button asking the stage alone sits there live over the run
    /// it already started: pressing it walks the coach through marking the court
    /// again and then returns immediately on `LocalAnalysisRunner.start`'s own
    /// guard. Android shipped exactly that, twice, before it was given
    /// `isDeviceRunInFlight`.
    static func canAnalyze(stage: AnalyzeStage, device: LocalAnalysisState = .idle) -> Bool {
        (stage == .local || stage == .failed) && !isDeviceRunInFlight(device)
    }

    /// What this row's status line says.
    ///
    /// The device speaks first when it has anything to say, the same precedence
    /// the Analytics list's `analyseAffordance` uses and for the same reason:
    /// this row's own button starts the device run, so that is the run it has to
    /// account for, and a cloud stage left over from an earlier attempt must not
    /// describe a device run happening now.
    ///
    /// Ports Android's `LocalVideoEntry.toRow`, down to dropping the
    /// percentage: the drawer leaves this line about 115pt, "Analyzing on
    /// device" fills that on its own, and appending a number truncated the one
    /// part of the label that changes. Home's banner and the chrome indicator
    /// both have room and both show it.
    static func rowStatus(
        stage: AnalyzeStage, progress: AnalyzeProgress?, device: LocalAnalysisState
    ) -> String? {
        switch device {
        case .failed(let message):
            // A device failure has no dialog of its own: the result dialog is
            // gated on stage == FAILED and a device run never moves the stage,
            // so this row is the only place a coach learns it broke.
            return "Analysis failed: \(message)"
        case .paused:
            return "Paused - keep Shuttl open"
        case .idle, .done:
            return text(stage: stage, progress: progress)
        case .preparing, .analysing, .cutting:
            guard let work = toDeviceWork(entryId: "", state: device) else {
                return text(stage: stage, progress: progress)
            }
            return BackgroundWorkKt.deviceWorkLabel(phase: work.phase, fraction: nil) + "…"
        }
    }

    /// Removal deletes the entry, its file and everything an analysis of it
    /// wrote; blocked while either pipeline is working on it.
    ///
    /// The shared rule covers the cloud one. The device run is asked separately
    /// for the same reason `canAnalyze` asks: it never moves the stage, and a
    /// removal mid-run deletes the source copy out from under a live decoder and
    /// the clips directory out from under a live encoder.
    static func canRemove(stage: AnalyzeStage, device: LocalAnalysisState = .idle) -> Bool {
        LocalVideoEntryKt.canRemoveLocalVideo(stage: stage) && !isDeviceRunInFlight(device)
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

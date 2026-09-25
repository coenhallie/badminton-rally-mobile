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
            return pausedText
        case .idle, .done:
            return text(stage: stage, progress: progress)
        case .preparing, .analysing, .cutting:
            guard let work = toDeviceWork(entryId: "", state: device) else {
                return text(stage: stage, progress: progress)
            }
            return BackgroundWorkKt.deviceWorkLabel(phase: work.phase, fraction: nil) + "…"
        }
    }

    /// What a paused device run says, on this row and on the match list's.
    static let pausedText = "Paused - keep Shuttl open"

    /// A device run as the shared match list sees it, or nil when there is no
    /// run to speak of: idle, or done, in which case the files it left say
    /// what it produced.
    ///
    /// The one reduction from this platform's run state to the list's, next to
    /// `rowStatus` so the two cannot word a run differently: the builder turns
    /// FAILED into the same "Analysis failed: ..." line and PAUSED into this
    /// message. A paused run keeps the phase it was frozen in, although the
    /// list shows only the message for it.
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

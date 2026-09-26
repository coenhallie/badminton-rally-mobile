import Shared
import XCTest
@testable import iosApp

final class LocalVideoStatusTests: XCTestCase {
    private func progress(upload: KotlinFloat? = nil, pipeline: KotlinFloat? = nil) -> AnalyzeProgress {
        AnalyzeProgress(entryId: "e1", uploadProgress: upload, pipelineProgress: pipeline)
    }

    func testLocalAndFailedShowNothing() {
        XCTAssertNil(LocalVideoStatus.text(stage: .local, progress: nil))
        XCTAssertNil(LocalVideoStatus.text(stage: .failed, progress: nil))
    }

    func testUploadingWithAndWithoutProgress() {
        XCTAssertEqual(
            LocalVideoStatus.text(stage: .uploading, progress: progress(upload: 0.42)),
            "Uploading 42%…"
        )
        XCTAssertEqual(
            LocalVideoStatus.text(stage: .uploading, progress: nil),
            "Uploading…"
        )
    }

    func testProcessingWithAndWithoutProgress() {
        XCTAssertEqual(
            LocalVideoStatus.text(stage: .processing, progress: progress(pipeline: 0.8)),
            "Analyzing 80%…"
        )
        XCTAssertEqual(
            LocalVideoStatus.text(stage: .processing, progress: nil),
            "Analyzing…"
        )
    }

    func testAnalyzed() {
        XCTAssertEqual(
            LocalVideoStatus.text(stage: .analyzed, progress: nil),
            "Analyzed"
        )
    }

    func testAnalyzeButtonLabel() {
        XCTAssertEqual(LocalVideoStatus.analyzeButtonLabel(stage: .local), "Analyze")
        XCTAssertEqual(LocalVideoStatus.analyzeButtonLabel(stage: .failed), "Re-analyze")
    }

    func testCanAnalyze() {
        XCTAssertTrue(LocalVideoStatus.canAnalyze(stage: .local))
        XCTAssertTrue(LocalVideoStatus.canAnalyze(stage: .failed))
        XCTAssertFalse(LocalVideoStatus.canAnalyze(stage: .uploading))
        XCTAssertFalse(LocalVideoStatus.canAnalyze(stage: .processing))
        XCTAssertFalse(LocalVideoStatus.canAnalyze(stage: .analyzed))
    }

    func testSpinnerOnlyWhileRunning() {
        XCTAssertTrue(LocalVideoStatus.isRunning(stage: .uploading))
        XCTAssertTrue(LocalVideoStatus.isRunning(stage: .processing))
        XCTAssertFalse(LocalVideoStatus.isRunning(stage: .local))
        XCTAssertFalse(LocalVideoStatus.isRunning(stage: .failed))
        XCTAssertFalse(LocalVideoStatus.isRunning(stage: .analyzed))
    }

    func testDetailsEditableOnlyBeforeThePipelineStarts() {
        // Metadata rides on the videos INSERT and the DB grants no UPDATE on
        // either column, so any stage past LOCAL means the row is already
        // written (or about to be) with what the user last saw. FAILED is
        // included deliberately: the stage alone cannot tell a run that failed
        // at UPLOAD, with no row yet, from one that failed after CREATE_ROW.
        XCTAssertTrue(LocalVideoStatus.canEditDetails(stage: .local))
        XCTAssertFalse(LocalVideoStatus.canEditDetails(stage: .uploading))
        XCTAssertFalse(LocalVideoStatus.canEditDetails(stage: .processing))
        XCTAssertFalse(LocalVideoStatus.canEditDetails(stage: .analyzed))
        XCTAssertFalse(LocalVideoStatus.canEditDetails(stage: .failed))
    }

    // MARK: - The device's own liveness

    func testTheAnalyzeButtonGoesForADeviceRunToo() {
        // A device run leaves the stage on LOCAL for its whole length. Asking
        // the stage alone leaves this button live over the run it started:
        // pressing it walks the coach through marking the court again and then
        // returns immediately on start()'s own guard - twelve taps, no effect,
        // no explanation, which is what Android shipped before it was given
        // isDeviceRunInFlight.
        XCTAssertTrue(LocalVideoStatus.canAnalyze(stage: .local, device: .idle))
        for device: LocalAnalysisState in [
            .preparing(message: "Preparing video"),
            .analysing(fraction: 0.4),
            .cutting(done: 2, total: 9),
            // Nothing was cancelled by the app going away, so there is still a
            // run here and still no second one to start.
            .paused(.analysing(fraction: 0.4)),
        ] {
            XCTAssertFalse(
                LocalVideoStatus.canAnalyze(stage: .local, device: device),
                "\(device) left the Analyze button live"
            )
        }
        // Settled: the button comes back, because a second run is a real thing
        // to ask for.
        XCTAssertTrue(LocalVideoStatus.canAnalyze(stage: .local, device: .failed(message: "boom")))
    }

}

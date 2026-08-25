import Shared
import XCTest
@testable import iosApp

final class LocalVideoStatusTests: XCTestCase {
    func testLocalAndFailedShowNothing() {
        XCTAssertNil(LocalVideoStatus.text(stage: .local, uploadProgress: nil, pipelineProgress: nil))
        XCTAssertNil(LocalVideoStatus.text(stage: .failed, uploadProgress: nil, pipelineProgress: nil))
    }

    func testUploadingWithAndWithoutProgress() {
        XCTAssertEqual(
            LocalVideoStatus.text(stage: .uploading, uploadProgress: 0.42, pipelineProgress: nil),
            "Uploading 42%…"
        )
        XCTAssertEqual(
            LocalVideoStatus.text(stage: .uploading, uploadProgress: nil, pipelineProgress: nil),
            "Uploading…"
        )
    }

    func testProcessingWithAndWithoutProgress() {
        XCTAssertEqual(
            LocalVideoStatus.text(stage: .processing, uploadProgress: nil, pipelineProgress: 0.8),
            "Analyzing 80%…"
        )
        XCTAssertEqual(
            LocalVideoStatus.text(stage: .processing, uploadProgress: nil, pipelineProgress: nil),
            "Analyzing…"
        )
    }

    func testAnalyzed() {
        XCTAssertEqual(
            LocalVideoStatus.text(stage: .analyzed, uploadProgress: nil, pipelineProgress: nil),
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

    func testCanRemoveBlockedOnlyMidPipeline() {
        XCTAssertTrue(LocalVideoStatus.canRemove(stage: .local))
        XCTAssertTrue(LocalVideoStatus.canRemove(stage: .failed))
        XCTAssertTrue(LocalVideoStatus.canRemove(stage: .analyzed))
        XCTAssertFalse(LocalVideoStatus.canRemove(stage: .uploading))
        XCTAssertFalse(LocalVideoStatus.canRemove(stage: .processing))
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
}

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

    func testCanRemoveBlockedOnlyMidPipeline() {
        XCTAssertTrue(LocalVideoStatus.canRemove(stage: .local))
        XCTAssertTrue(LocalVideoStatus.canRemove(stage: .failed))
        XCTAssertTrue(LocalVideoStatus.canRemove(stage: .analyzed))
        XCTAssertFalse(LocalVideoStatus.canRemove(stage: .uploading))
        XCTAssertFalse(LocalVideoStatus.canRemove(stage: .processing))
    }
}

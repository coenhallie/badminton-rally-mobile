import Shared
import XCTest
@testable import iosApp

/// Tests the Swift decision ladder and Kotlin bridge for `ExercisesPanel`,
/// mirroring androidApp's `ExercisesPanelTest` and `BasePositionPanelTests`.
final class ExercisesPanelTests: XCTestCase {

    private func track(samples: Int) -> PlayerTrack {
        PlayerTrack(
            samples: (0..<samples).map {
                PlayerSample(frame: Int32($0), courtPosition: Point(x: 3.05, y: 10.0))
            },
            framesWithPose: Int32(samples),
            rejections: [:]
        )
    }

    func testUnloadedAnalysisSaysSo() {
        XCTAssertEqual(
            exercisesMessage(track: nil, windows: [], report: nil, measured: true),
            ExercisesCopy.shared.NOT_LOADED
        )
    }

    func testEmptyTrackUsesHeatmapWording() {
        let empty = PlayerTrack(samples: [], framesWithPose: 0, rejections: [:])
        XCTAssertEqual(
            exercisesMessage(track: empty, windows: [RallyWindow(index: 1, startSeconds: 0, endSeconds: 4)], report: nil, measured: true),
            whyEmpty(empty)
        )
    }

    func testUnboundedWindowsSaysWindowsWereNotKept() {
        XCTAssertEqual(
            exercisesMessage(
                track: track(samples: 100),
                windows: [RallyWindow(index: 1, startSeconds: 0, endSeconds: 0)],
                report: nil,
                measured: true
            ),
            ExercisesCopy.shared.NO_BOUNDED_WINDOWS
        )
    }

    func testWaitsForMeasurementBeforeReporting() {
        XCTAssertNil(
            exercisesMessage(
                track: track(samples: 100),
                windows: [RallyWindow(index: 1, startSeconds: 0, endSeconds: 4)],
                report: nil,
                measured: false
            )
        )
    }

    func testMeasuredNilReportExplainsNoCountedRallies() {
        XCTAssertEqual(
            exercisesMessage(
                track: track(samples: 100),
                windows: [RallyWindow(index: 1, startSeconds: 0, endSeconds: 4)],
                report: nil,
                measured: true
            ),
            ExercisesCopy.shared.NO_COUNTED_RALLIES
        )
    }

    func testMeasuredReportRendersPanelContent() {
        let windows = [RallyWindow(index: 1, startSeconds: 0, endSeconds: 4)]
        let statuses: [ExerciseReportStatus] = [.insufficientData, .balanced, .suggestions]
        for status in statuses {
            let report = ExerciseReport(status: status, message: "msg", summary: nil, suggestions: [])
            XCTAssertNil(exercisesMessage(track: track(samples: 100), windows: windows, report: report, measured: true))
        }
    }

    func testAdviseExercisesForTrackWorksFromSwiftForNearAndFarWithProfileArms() throws {
        let fps = 20.0
        let windows = (0..<4).map { r in
            RallyWindow(index: Int32(r + 1), startSeconds: Double(r) * 3.0, endSeconds: Double(r) * 3.0 + 2.0)
        }
        // Near biased +0.60 m to player right (x = 3.65); Far biased +0.60 m to player right facing net (x = 2.45).
        let nearSamples = (0..<4).flatMap { r in
            (0..<40).map { f in PlayerSample(frame: Int32(r * 60 + f), courtPosition: Point(x: 3.65, y: 10.0)) }
        }
        let farSamples = (0..<4).flatMap { r in
            (0..<40).map { f in PlayerSample(frame: Int32(r * 60 + f), courtPosition: Point(x: 2.45, y: 3.4)) }
        }
        let names = CourtSideNames(near: "Marco", far: "Jip", nearArm: .right, farArm: .left)

        let nearReport = ExerciseAdvisorKt.adviseExercisesForTrack(
            track: PlayerTrack(samples: nearSamples, framesWithPose: Int32(nearSamples.count), rejections: [:]),
            fps: fps,
            windows: windows,
            side: .near,
            racketArm: CourtSideNamesKt.profileArm(side: .near, names: names)
        )
        let farReport = ExerciseAdvisorKt.adviseExercisesForTrack(
            track: PlayerTrack(samples: farSamples, framesWithPose: Int32(farSamples.count), rejections: [:]),
            fps: fps,
            windows: windows,
            side: .far,
            racketArm: CourtSideNamesKt.profileArm(side: .far, names: names)
        )

        XCTAssertEqual(nearReport.status, .suggestions)
        XCTAssertEqual(nearReport.suggestions.first?.finding, .lateralBias)
        XCTAssertTrue(try XCTUnwrap(nearReport.suggestions.first?.evidence).contains("forehand side"))

        XCTAssertEqual(farReport.status, .suggestions)
        XCTAssertEqual(farReport.suggestions.first?.finding, .lateralBias)
        XCTAssertTrue(try XCTUnwrap(farReport.suggestions.first?.evidence).contains("backhand side"))
    }
}

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

    func testEvaluateReportsBySidePrecomputesAllSidesWithRespectiveArms() throws {
        let windows = (0..<4).map { r in
            RallyWindow(index: Int32(r + 1), startSeconds: Double(r) * 3.0, endSeconds: Double(r) * 3.0 + 2.0)
        }
        let nearSamples = (0..<4).flatMap { r in
            (0..<40).map { f in PlayerSample(frame: Int32(r * 60 + f), courtPosition: Point(x: 3.65, y: 10.0)) }
        }
        let farSamples = (0..<4).flatMap { r in
            (0..<40).map { f in PlayerSample(frame: Int32(r * 60 + f), courtPosition: Point(x: 2.45, y: 3.4)) }
        }
        let source = HeatmapSource(
            tracks: [
                PlayerTrackStore.SideTrack(
                    side: .near,
                    track: PlayerTrack(samples: nearSamples, framesWithPose: Int32(nearSamples.count), rejections: [:])
                ),
                PlayerTrackStore.SideTrack(
                    side: .far,
                    track: PlayerTrack(samples: farSamples, framesWithPose: Int32(farSamples.count), rejections: [:])
                ),
            ],
            fps: 20.0
        )
        let names = CourtSideNames(near: "Marco", far: "Jip", nearArm: .right, farArm: .left)

        let reports = evaluateReportsBySide(source: source, windows: windows, sideNames: names)

        XCTAssertEqual(Set(reports.keys), Set([CourtSide.near, CourtSide.far]))
        XCTAssertTrue(try XCTUnwrap(reports[.near]?.suggestions.first?.evidence).contains("forehand side"))
        XCTAssertTrue(try XCTUnwrap(reports[.far]?.suggestions.first?.evidence).contains("backhand side"))
    }

    func testExerciseVisualsBridgeSupportsSamplingAndLeftHandMirroring() throws {
        let onCourt = ExerciseVisualsKt.visualFor(exerciseId: "forecourt-lunge-net-kill")
        let path = try XCTUnwrap(onCourt.courtPath)

        let rightPose = ExerciseVisualsKt.samplePose(sequence: onCourt.pose, phase01: 0.35, mirrorX: false)
        let leftPose = ExerciseVisualsKt.samplePose(sequence: onCourt.pose, phase01: 0.35, mirrorX: true)
        XCTAssertEqual(leftPose.label, rightPose.label)
        XCTAssertEqual(leftPose.figure.head.x, 1.0 - rightPose.figure.head.x, accuracy: 0.001)
        let rightTip = try XCTUnwrap(rightPose.figure.racketTip)
        let leftTip = try XCTUnwrap(leftPose.figure.racketTip)
        XCTAssertEqual(leftTip.x, 1.0 - rightTip.x, accuracy: 0.001)

        let rightCourt = ExerciseVisualsKt.sampleCourtPoint(path: path, phase01: 0.25, mirrorX: false)
        let leftCourt = ExerciseVisualsKt.sampleCourtPoint(path: path, phase01: 0.25, mirrorX: true)
        let width = Float(Court.shared.WIDTH_DOUBLES)
        XCTAssertEqual(leftCourt.x, width - rightCourt.x, accuracy: 0.002)
        XCTAssertEqual(leftCourt.y, rightCourt.y, accuracy: 0.002)

        let offCourt = ExerciseVisualsKt.visualFor(exerciseId: "split-squat-endurance")
        XCTAssertNil(offCourt.courtPath)
        XCTAssertNil(ExerciseVisualsKt.samplePose(sequence: offCourt.pose, phase01: 0.5, mirrorX: false).figure.racketTip)
    }

    func testDrillLibraryFiltersCoverEveryCatalogExerciseFromSwift() {
        let all = ExerciseCatalog.shared.all
        XCTAssertEqual(all.count, 38)
        XCTAssertEqual(ExerciseCatalog.shared.filtered(filter: .all).count, all.count)
        let subFilters = ExerciseCatalog.shared.allFilters.filter { $0 != .all }
        let combinedIds = subFilters.flatMap { ExerciseCatalog.shared.filtered(filter: $0).map(\.id) }
        XCTAssertEqual(combinedIds.count, all.count)
        XCTAssertEqual(Set(combinedIds).count, all.count)
        for exercise in all {
            XCTAssertEqual(ExerciseVisualsKt.visualFor(exerciseId: exercise.id).exerciseId, exercise.id)
        }
    }
}

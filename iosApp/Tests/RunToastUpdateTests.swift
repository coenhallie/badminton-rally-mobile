import Foundation
import Shared
import XCTest
@testable import iosApp

/// HomeView's announcement rule: which finished runs reach the list's toast,
/// and how a run landing while a toast shows joins it. The seen-set rule
/// itself is shared and pinned in RunAnnouncementTest; these pin what iOS
/// does around it.
final class RunToastUpdateTests: XCTestCase {

    private let empty = PlayerTrack(samples: [], framesWithPose: 0, rejections: [:])

    private func done(clips: Int = 0, skeletonSaved: Bool = false) -> LocalAnalysisState {
        .done(LocalAnalysisState.Done(
            rallies: clips, shuttleVisible: 0, totalFrames: 100,
            clips: (0..<clips).map {
                PlayerTrackStore.Clip(
                    index: $0 + 1, url: URL(fileURLWithPath: "/tmp/\($0).mp4"),
                    startSeconds: 0, endSeconds: 1
                )
            },
            elapsedSeconds: 1, playerTrack: empty, fps: 30, skeletonSaved: skeletonSaved
        ))
    }

    private func update(
        device: [String: LocalAnalysisState] = [:],
        cloud: Set<String> = [],
        seen: Set<String> = [],
        onHome: Bool = true,
        showing: Set<String>? = nil
    ) -> RunToastUpdate {
        runToastUpdate(deviceStates: device, cloudFinished: cloud, seen: seen, onHome: onHome, showing: showing)
    }

    func testADeviceRunThatFoundNothingIsNotAnnounced() {
        let result = update(device: ["empty": done(), "rallies": done(clips: 2)])
        XCTAssertEqual(result.toastRuns, ["rallies"])
    }

    func testADeviceRunWithOnlyASkeletonIsAnnounced() {
        XCTAssertEqual(update(device: ["pose": done(skeletonSaved: true)]).toastRuns, ["pose"])
    }

    func testARunStillWorkingIsNotAnnounced() {
        XCTAssertNil(update(device: ["busy": .analysing(fraction: 0.5)]).toastRuns)
    }

    func testCloudRunsAreAnnouncedUnfiltered() {
        XCTAssertEqual(update(cloud: ["v1"]).toastRuns, ["v1"])
    }

    func testAVideoFinishedBothWaysIsOneRun() {
        XCTAssertEqual(update(device: ["e1": done(clips: 1)], cloud: ["e1"]).toastRuns, ["e1"])
    }

    func testARunLandingWhileAToastShowsJoinsIt() {
        let result = update(cloud: ["a", "b"], seen: ["a"], showing: ["a"])
        XCTAssertEqual(result.toastRuns, ["a", "b"])
    }

    func testOnceTheToastHasGoneANewOneStartsFromTheNewRunsAlone() {
        let result = update(cloud: ["a", "b"], seen: ["a"], showing: nil)
        XCTAssertEqual(result.toastRuns, ["b"])
    }

    func testNothingNewLeavesTheToastAsItIs() {
        let result = update(cloud: ["a"], seen: ["a"], showing: ["a"])
        XCTAssertNil(result.toastRuns)
        XCTAssertEqual(result.seen, ["a"])
    }

    // The seen-set rule, as it reaches the toast: a run that lands while the
    // list is not in front (a push, a sheet, the app in the background) is
    // marked seen and does not announce itself when the coach comes back.
    func testARunLandingOutOfSightIsMarkedSeenAndNeverAnnounced() {
        let away = update(cloud: ["a"], onHome: false)
        XCTAssertNil(away.toastRuns)
        XCTAssertEqual(away.seen, ["a"])
        XCTAssertNil(update(cloud: ["a"], seen: away.seen, onHome: true).toastRuns)
    }
}

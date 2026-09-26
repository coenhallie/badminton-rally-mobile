import Foundation
import Shared
import XCTest
@testable import iosApp

/// Whether a finished device run is announced as analyzed: HomeView's
/// `announce` drops the ones that found nothing, from the run's own outcome.
/// Port of androidApp's `DeviceRunOutputTest`, case for case.
final class DeviceRunOutputTests: XCTestCase {

    func testARunThatFoundNothingHasNothingToAnnounce() {
        XCTAssertFalse(done().foundSomething)
    }

    func testACutRallyIsOutput() {
        let clip = PlayerTrackStore.Clip(
            index: 1, url: URL(fileURLWithPath: "/tmp/1.mp4"), startSeconds: 1, endSeconds: 8
        )
        XCTAssertTrue(done(clips: [clip]).foundSomething)
    }

    func testATrackIsOutput() {
        let track = PlayerTrack(
            samples: [PlayerSample(frame: 0, courtPosition: Point(x: 1, y: 2))],
            framesWithPose: 1, rejections: [:]
        )
        XCTAssertTrue(done(track: track).foundSomething)
    }

    // The poses can be found while the near/far gate keeps no player, which
    // leaves the track empty and the skeleton stored.
    func testASkeletonAloneIsOutput() {
        XCTAssertTrue(done(skeletonSaved: true).foundSomething)
    }

    private func done(
        clips: [PlayerTrackStore.Clip] = [],
        track: PlayerTrack = PlayerTrack(samples: [], framesWithPose: 0, rejections: [:]),
        skeletonSaved: Bool = false
    ) -> LocalAnalysisState.Done {
        LocalAnalysisState.Done(
            rallies: 0, shuttleVisible: 0, totalFrames: 163, clips: clips,
            elapsedSeconds: 43, playerTrack: track, fps: 30, skeletonSaved: skeletonSaved
        )
    }
}

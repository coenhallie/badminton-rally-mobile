import XCTest
import Shared

/// Pins the wording fix from finding 6 of the 2026-08-28 review, on the iOS side
/// of `deleteScoreMatchOrMessage` / `ClipListModel.deleteScoreMatch`'s `catch`
/// fallback. `scoreLogDeleteFailedMessage` is plain Kotlin in `iosMain`, which
/// `:shared:jvmTest` never compiles or runs, so this is its only test coverage.
/// Android's mirror lives in `ClipListViewModelDeleteTest`'s
/// `deleteBoundMatch_does_not_touch_the_video_when_the_score_log_delete_fails_on_the_server`.
final class ScoreLogDeleteMessageTests: XCTestCase {

    func testAScoreOnlyMatchWithNoVideoSaysOnlyThatItIsGone() {
        // Nothing else exists to lie about - a video-and-clips claim here would
        // be the message describing a delete that never involved either.
        XCTAssertEqual(
            SwiftInteropKt.scoreLogDeleteFailedMessage(hasVideo: false),
            "Couldn't delete the match everywhere. It's gone from this phone."
        )
    }

    func testABoundMatchNamesTheVideoAndClipsThatStayed() {
        // deleteBoundMatch never reaches the video delete when this message
        // fires, so the video and its clips are completely untouched, not
        // merely undeleted on the server - the old wording did not say so.
        XCTAssertEqual(
            SwiftInteropKt.scoreLogDeleteFailedMessage(hasVideo: true),
            "Couldn't delete the match everywhere. The match is gone from this phone, " +
            "but its video and clips are still there."
        )
    }
}

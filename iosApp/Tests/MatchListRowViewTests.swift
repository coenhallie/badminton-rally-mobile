import XCTest
@testable import iosApp

/// `thumbnailFallback`: what a row's thumbnail tile shows once every picture
/// has failed or there was none to try. Port of Android's
/// `ThumbnailFallbackTest`.
final class MatchListRowViewTests: XCTestCase {
    func testAVideoWithNoScoreYetShowsTheVideoGlyph() {
        // A row with a video whose picture would not load is still a video: it
        // gets the video glyph, never the score's "#", which would read as a
        // scored match.
        XCTAssertEqual(thumbnailFallback(hasVideo: true, hasScore: false), .videoGlyph)
    }

    func testTheScoreWinsWheneverThereIsOne() {
        // The tile has room for "21-18": a scored match with a video still
        // shows the score once every picture has failed.
        XCTAssertEqual(thumbnailFallback(hasVideo: true, hasScore: true), .score)
        XCTAssertEqual(thumbnailFallback(hasVideo: false, hasScore: true), .score)
    }

    func testNeitherAVideoNorAScoreShowsTheScoreGlyph() {
        // "#" is left for a scored match nobody has started, with no video at
        // all.
        XCTAssertEqual(thumbnailFallback(hasVideo: false, hasScore: false), .scoreGlyph)
    }

    // MARK: - CoverRetryKey

    /// Whether a failed cover clip deserves another try. `thumbnailStoragePath`
    /// alone cannot say so - it is a stable backend key for the clip, not a
    /// token for the fetch attempt, so a coach who fails once offline and then
    /// pulls to refresh once back online is still looking at the same path on
    /// the same clip. `refreshGeneration` is the half of the key that actually
    /// moves when that happens.
    func testTheKeyChangesWhenTheClipChanges() {
        XCTAssertNotEqual(
            CoverRetryKey(path: "a", refreshGeneration: 1),
            CoverRetryKey(path: "b", refreshGeneration: 1)
        )
    }

    func testTheKeyChangesWhenARefreshCompletesEvenOnTheSameClip() {
        // The case this key exists for: the same cover, offline once, then a
        // refresh - nothing about `path` moved, but the generation did.
        XCTAssertNotEqual(
            CoverRetryKey(path: "a", refreshGeneration: 1),
            CoverRetryKey(path: "a", refreshGeneration: 2)
        )
    }

    func testTheKeyIsStableWhenNeitherHalfMoved() {
        // No spurious reset: an unrelated re-render must not flip `coverFailed`
        // back to false while nothing this key cares about has changed.
        XCTAssertEqual(
            CoverRetryKey(path: "a", refreshGeneration: 1),
            CoverRetryKey(path: "a", refreshGeneration: 1)
        )
    }

    func testANilPathIsItsOwnStableValue() {
        // A match with no cover at all: nil equals nil, so a row that never
        // had a cover does not look like a change on every redraw either.
        XCTAssertEqual(
            CoverRetryKey(path: nil, refreshGeneration: 1),
            CoverRetryKey(path: nil, refreshGeneration: 1)
        )
        XCTAssertNotEqual(
            CoverRetryKey(path: nil, refreshGeneration: 1),
            CoverRetryKey(path: "a", refreshGeneration: 1)
        )
    }
}

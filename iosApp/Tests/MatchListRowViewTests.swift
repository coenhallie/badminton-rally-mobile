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
}

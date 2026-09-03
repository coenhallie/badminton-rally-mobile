import XCTest
@testable import iosApp

/// The hero's copy and its wrap-around, pinned so neither drifts from the mock
/// or from the Android side. The rotation is the one piece of logic on an
/// otherwise static screen, and a timer is not needed to test it.
final class HeroTickerTests: XCTestCase {
    func testCopyMatchesTheMockVerbatim() {
        XCTAssertEqual(HeroTicker.leadLine, "Your game,")
        XCTAssertEqual(HeroTicker.phrases, [
            "clipped rally by rally.",
            "mapped as heatmaps.",
            "tracked as skeletons.",
            "annotated and shared.",
        ])
    }

    func testAdvancesThroughEveryPhrase() {
        var seen: [Int] = [0]
        var i = 0
        for _ in 1..<HeroTicker.phrases.count {
            i = HeroTicker.next(after: i)
            seen.append(i)
        }
        XCTAssertEqual(seen, [0, 1, 2, 3], "every phrase must be reachable in order")
    }

    func testWrapsBackToTheFirstPhrase() {
        XCTAssertEqual(HeroTicker.next(after: HeroTicker.phrases.count - 1), 0)
    }

    func testOutOfRangeIndexDoesNotCrashOrEscape() {
        // Defensive: state restored from a stale value must not index out of
        // bounds. Any input lands back inside the array.
        for i in [-5, 99, Int.max] {
            let n = HeroTicker.next(after: i)
            XCTAssertTrue(HeroTicker.phrases.indices.contains(n), "next(after: \(i)) escaped the array")
        }
    }
}

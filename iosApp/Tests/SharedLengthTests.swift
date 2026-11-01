import Shared
import XCTest
@testable import iosApp

/// A capped field counts and cuts as shared judges: Kotlin's `String.length`,
/// UTF-16 units, so "40/40" is never shown over a name the save refuses.
final class SharedLengthTests: XCTestCase {

    func testAnEmojiCountsAsSharedCountsIt() {
        XCTAssertEqual("Marco".sharedLength, 5)
        // One character to Swift, two units to Kotlin.
        XCTAssertEqual("🏸".sharedLength, 2)
        XCTAssertEqual("Li 🏸".sharedLength, 5)
    }

    func testTheCapNeverSplitsACharacter() {
        // 39 letters and a racket: 41 units. The racket does not fit whole,
        // so it goes whole rather than leaving half a surrogate pair.
        let name = String(repeating: "a", count: 39) + "🏸"
        let capped = name.prefix(sharedLength: 40)
        XCTAssertEqual(capped, String(repeating: "a", count: 39))
        XCTAssertEqual(capped.sharedLength, 39)
        // A flag is two pairs in one character: kept or left out whole.
        XCTAssertEqual("🇳🇱x".prefix(sharedLength: 3), "")
        XCTAssertEqual("🇳🇱x".prefix(sharedLength: 4), "🇳🇱")
    }

    func testANameAtTheCapIsOneTheSharedRuleAccepts() {
        let capped = (String(repeating: "b", count: 38) + "🏸🏸").prefix(sharedLength: Int(MatchDetailsKt.MAX_PLAYER_NAME))
        XCTAssertLessThanOrEqual(capped.sharedLength, Int(MatchDetailsKt.MAX_PLAYER_NAME))
        XCTAssertNil(MatchPlayersKt.matchPlayersProblem(
            players: MatchPlayers(homePlayerId: nil, awayPlayerId: nil, homePlayers: [capped], awayPlayers: []),
            format: nil
        ))
        XCTAssertNil(PlayerKt.playerNameProblem(name: capped))
    }
}

import XCTest
import Shared
@testable import iosApp

/// A Kotlin suspend function that throws reaches Swift as an `NSError` only when
/// it is declared `@Throws`. Without the annotation Kotlin/Native treats the
/// exception as unhandled and terminates the process, so the `catch` beside the
/// call never runs and a pull to refresh with no signal closes the app.
///
/// Only this bundle can check that: the rule lives in the Objective-C bridge, and
/// every Kotlin test calls these functions from Kotlin, where a throw is a throw.
final class KotlinThrowBridgingTests: XCTestCase {
    func test_a_refresh_with_no_signal_is_an_error_swift_can_catch() async {
        let clips = IosTestDoublesKt.testOfflineClipsRepository()
        do {
            try await clips.refresh()
            XCTFail("an offline refresh should have thrown")
        } catch {
            XCTAssertTrue("\(error)".contains("offline"), "unexpected error: \(error)")
        }
    }

    func test_a_list_with_no_signal_is_an_error_swift_can_catch() async {
        let clips = IosTestDoublesKt.testOfflineClipsRepository()
        do {
            _ = try await clips.listClips()
            XCTFail("an offline list should have thrown")
        } catch {
            XCTAssertTrue("\(error)".contains("offline"), "unexpected error: \(error)")
        }
    }
}

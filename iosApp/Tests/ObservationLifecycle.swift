import XCTest
@testable import iosApp

/// What a model's `start()` owes the `.task` that runs it, as two probes every
/// model with flow loops can be held to.
///
/// SwiftUI cancels a `.task` when its view leaves the screen and runs it again
/// when the view comes back. A `start()` whose loops are structured children of
/// that task stops with it and starts with it. One that spawns `Task {}` loops
/// does neither: the loops outlive the screen, a return stacks another set on
/// top, and because each captures the model, the model is never released. That
/// last part is what makes the defect observable from outside without a counter
/// planted in product code - a model still alive after its only owner let go is
/// a model something immortal is still holding.
@MainActor
enum ObservationLifecycle {

    /// A box rather than a captured `var`, which a `@Sendable` task closure may
    /// not mutate.
    final class Flag: @unchecked Sendable {
        private let lock = NSLock()
        private var value = false
        var isSet: Bool { lock.lock(); defer { lock.unlock() }; return value }
        func set() { lock.lock(); value = true; lock.unlock() }
    }

    /// Polls, because every signal here crosses the Kotlin bridge on a coroutine
    /// this test does not schedule.
    static func wait(seconds: Double = 5, until condition: () -> Bool) async -> Bool {
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline {
            if condition() { return true }
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
        return condition()
    }

    /// Runs `start` the way a `.task` does, cancels it the way a disappearing
    /// view does, and reports whether the call came back.
    ///
    /// The pause before cancelling is so the loops are actually subscribed: a
    /// task cancelled before its first `for await` proves nothing about one
    /// cancelled in the middle of a collection, which is the case a push causes.
    static func runAndCancel(_ start: @escaping @MainActor () async -> Void) async -> Bool {
        let returned = Flag()
        let task = Task { @MainActor in
            await start()
            returned.set()
        }
        try? await Task.sleep(nanoseconds: 200_000_000)
        task.cancel()
        return await wait { returned.isSet }
    }
}

import Shared
import SwiftUI
import XCTest
@testable import iosApp

/// Who decides whether the phone may lock, and what they decide.
@MainActor
final class KeepAwakeTests: XCTestCase {

    private var window: UIWindow?

    override func tearDown() {
        window?.isHidden = true
        window = nil
        UIApplication.shared.isIdleTimerDisabled = false
    }

    private func show(_ view: some View) throws {
        let scene = try XCTUnwrap(
            UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first,
            "no window scene: these tests need the app as their host"
        )
        let window = UIWindow(windowScene: scene)
        window.rootViewController = UIHostingController(rootView: view)
        window.makeKeyAndVisible()
        self.window = window
    }

    func testLeavingTheBoardDoesNotLetThePhoneLockUnderARun() async throws {
        // The reported defect, on the real view. The board set the idle timer
        // itself and cleared it on the way out, so leaving it while an upload or
        // a device analysis was running turned the screen lock back on under the
        // run - and RootView, whose own input had not changed, never put it back.
        let rally = RallyAppIosKt.createRallyApp(
            url: "https://test.supabase.co",
            anonKey: "test-anon-key",
            deleteLocalVideoFile: { _ in },
            deleteLocalAnalysis: { _, _ in }
        )
        let keepAwake = KeepAwake()
        try show(
            NavigationStack {
                // An id the store does not have: the board draws its "no longer
                // on this phone" state, which has the same lifecycle and leaves
                // nothing behind in the simulator's defaults.
                ScoringView(
                    rally: rally, scoreLogId: "keep-awake-\(UUID().uuidString)",
                    matchPageAlreadyOpen: false, onFinished: { _ in }
                )
            }
            .environment(keepAwake)
        )
        let appeared = await ObservationLifecycle.wait { !keepAwake.boardsOnScreen.isEmpty }
        XCTAssertTrue(appeared, "the board did not register itself as a reason to stay awake")

        // RootView's part, played by hand: a run is in flight, so the timer is off.
        UIApplication.shared.isIdleTimerDisabled = true

        window?.rootViewController = UIViewController()
        let left = await ObservationLifecycle.wait { keepAwake.boardsOnScreen.isEmpty }
        XCTAssertTrue(left, "the board did not withdraw its reason on the way out")
        XCTAssertTrue(
            UIApplication.shared.isIdleTimerDisabled,
            "leaving the board turned the screen lock back on under a run in flight"
        )
    }

    // MARK: - The decision

    func testNothingHappeningLetsThePhoneLock() {
        XCTAssertFalse(shouldKeepAwake(keepAwakeReasons(uploading: false, analysing: false, boardsOnScreen: 0)))
    }

    func testEachReasonAloneKeepsThePhoneAwake() {
        XCTAssertEqual(keepAwakeReasons(uploading: true, analysing: false, boardsOnScreen: 0), [.upload])
        XCTAssertEqual(keepAwakeReasons(uploading: false, analysing: true, boardsOnScreen: 0), [.deviceAnalysis])
        XCTAssertEqual(keepAwakeReasons(uploading: false, analysing: false, boardsOnScreen: 1), [.scoringBoard])
        for reason in KeepAwakeReason.allCases {
            XCTAssertTrue(shouldKeepAwake([reason]), "\(reason) alone did not keep the phone awake")
        }
    }

    func testLeavingTheBoardMidRunLeavesTheRunsReasonStanding() {
        // The defect as a decision: the board going away removes ITS reason and
        // no one else's.
        let onTheBoard = keepAwakeReasons(uploading: false, analysing: true, boardsOnScreen: 1)
        let afterLeaving = keepAwakeReasons(uploading: false, analysing: true, boardsOnScreen: 0)
        XCTAssertTrue(shouldKeepAwake(onTheBoard))
        XCTAssertTrue(shouldKeepAwake(afterLeaving))
        XCTAssertEqual(afterLeaving, [.deviceAnalysis])
    }

    func testARunFinishingUnderTheBoardLeavesTheBoardsReasonStanding() {
        // The same defect from the other side: RootView's old input went false
        // when the upload ended and it turned the lock back on under a coach
        // still scoring.
        XCTAssertTrue(shouldKeepAwake(keepAwakeReasons(uploading: false, analysing: false, boardsOnScreen: 1)))
    }

    func testABoardThatAppearsTwiceIsStillOneBoard() {
        // SwiftUI may deliver onAppear more than once for one appearance. A
        // counter would then never come back to zero and the phone would never
        // lock again; a set of ids cannot over-count.
        let keepAwake = KeepAwake()
        let board = UUID()
        keepAwake.boardAppeared(board)
        keepAwake.boardAppeared(board)
        keepAwake.boardDisappeared(board)
        XCTAssertTrue(keepAwake.boardsOnScreen.isEmpty)
    }

    func testOneBoardReplacingAnotherNeverLeavesAGap() {
        // A new board's onAppear and the old one's onDisappear arrive in no
        // promised order. Whichever comes first, there is a board on screen
        // throughout.
        let keepAwake = KeepAwake()
        let old = UUID(), new = UUID()
        keepAwake.boardAppeared(old)
        keepAwake.boardAppeared(new)
        keepAwake.boardDisappeared(old)
        XCTAssertEqual(keepAwake.boardsOnScreen, [new])
    }
}

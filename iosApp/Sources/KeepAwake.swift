import SwiftUI

/// Why the phone must not lock right now.
enum KeepAwakeReason: Hashable, CaseIterable {
    /// An upload dies with the screen: iOS suspends the app soon after the lock.
    case upload
    /// So does an on-device analysis, twenty minutes into it.
    case deviceAnalysis
    /// A coach scoring courtside looks down once a rally, not once a timeout.
    case scoringBoard
}

/// Every reason that currently holds, from the three things that can supply one.
///
/// Pure, and apart from `RootView`, so the rule is tested without a screen. The
/// inputs are what `RootView` can actually see rather than a set someone has to
/// keep in step: a reason derived on every evaluation cannot be left behind by
/// a writer that forgot to withdraw it.
func keepAwakeReasons(uploading: Bool, analysing: Bool, boardsOnScreen: Int) -> Set<KeepAwakeReason> {
    var reasons: Set<KeepAwakeReason> = []
    if uploading { reasons.insert(.upload) }
    if analysing { reasons.insert(.deviceAnalysis) }
    if boardsOnScreen > 0 { reasons.insert(.scoringBoard) }
    return reasons
}

/// Any reason is enough, and only the absence of all of them lets the phone
/// lock. The defect this replaced was two writers each clearing the flag when
/// THEIR reason ended.
func shouldKeepAwake(_ reasons: Set<KeepAwakeReason>) -> Bool {
    !reasons.isEmpty
}

/// Where a screen says it needs the phone awake, instead of saying so to UIKit.
///
/// `RootView` owns one, puts it in the environment, and is the only thing in
/// the app that writes `isIdleTimerDisabled`. The scoring board used to write
/// it as well - on in its `.task`, off in `onDisappear` - so leaving the board
/// while an upload or an analysis was running turned the lock back on under the
/// run, and `RootView`'s `onChange` never put it back because its own input had
/// not changed.
///
/// Ids rather than a count. SwiftUI may deliver `onAppear` twice for one
/// appearance, and when one board replaces another the new `onAppear` and the
/// old `onDisappear` arrive in no promised order; a set is right under both and
/// a counter is right under neither.
@Observable @MainActor
final class KeepAwake {
    private(set) var boardsOnScreen: Set<UUID> = []

    func boardAppeared(_ id: UUID) { boardsOnScreen.insert(id) }
    func boardDisappeared(_ id: UUID) { boardsOnScreen.remove(id) }
}

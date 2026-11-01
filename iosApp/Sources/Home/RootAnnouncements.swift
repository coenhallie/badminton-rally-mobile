import Foundation
import Shared

/// The root toast's queue: what shows, what waits its turn, and which message
/// is the finished-run one. HomeView owns one and drives it; the rules live
/// here, apart from the view, so they are tested (`RootAnnouncementsTests`).
///
/// One message shows at a time, and only while a list screen is in front with
/// nothing above it (`deliver(listInFront:)`): a toast drawn under a pushed
/// screen would time out unseen. The finished-run toast is replaced where it
/// stands, showing or waiting, so a second run joins it ("2 matches are
/// analyzed") rather than queuing a second toast or evicting a failure.
///
/// The message ends by its own time or action, or when the coach leaves the
/// list screens (`listsLeft`), which HomeView reads off its path rather than
/// off a screen disappearing: see `View.shuttlToast(_:bottomInset:endsWhenScreenGoes:)`.
struct RootToasts {
    /// The message on screen, drawn by Players and All matches.
    private(set) var showing: ToastMessage? = nil
    /// Waiting their turn, oldest first.
    private(set) var waiting: [ToastMessage] = []
    /// The runs the finished-run toast names, while it is showing or waiting.
    private var runs: Set<String> = []
    private var runToastId: UUID? = nil
    /// The runs the finished-run toast already names, while it is showing or
    /// waiting; nil once it has gone. What `runToastUpdate` merges with.
    var pendingRuns: Set<String>? {
        guard let id = runToastId,
              showing?.id == id || waiting.contains(where: { $0.id == id })
        else { return nil }
        return runs
    }

    /// Queues a message behind any already waiting.
    mutating func post(_ message: ToastMessage) {
        waiting.append(message)
    }

    /// The finished-run toast, now naming `runIds`: in place of the one
    /// showing or waiting, or queued when there is none.
    mutating func announce(runs runIds: Set<String>, _ message: ToastMessage) {
        if let old = runToastId, showing?.id == old {
            showing = message
        } else if let old = runToastId, let at = waiting.firstIndex(where: { $0.id == old }) {
            waiting[at] = message
        } else {
            waiting.append(message)
        }
        runs = runIds
        runToastId = message.id
    }

    /// Shows the oldest waiting message when nothing is showing and a list
    /// screen is in front with nothing above it.
    mutating func deliver(listInFront: Bool) {
        guard showing == nil, listInFront, !waiting.isEmpty else { return }
        showing = waiting.removeFirst()
    }

    /// The message on screen reports its own end: its time ran out, or its
    /// action ran.
    mutating func ended(_ message: ToastMessage) {
        guard showing == message else { return }
        showing = nil
    }

    /// The coach left the list screens for another (a match, Settings, the
    /// camera): the message on screen goes with them rather than waiting
    /// there for their return. Moving between Players and All matches is not
    /// leaving: both draw the message, so it goes on showing on the other.
    /// What waits its turn stays waiting.
    mutating func listsLeft() {
        showing = nil
    }
}

/// The failed-run dialog: which standalone video it is about, and when it may
/// show. Pure, for `RootAnnouncementsTests`.
enum FailedRunDialog {
    /// The entry the dialog is about, given the one it already holds and the
    /// store as it is now. The held entry as the store has it now while it
    /// still waits (so a dialog held back shows what is true when it does
    /// show), the first one waiting otherwise, nil when none does. A video
    /// claimed by a match fails on its match's row and page instead.
    static func entry(held: LocalVideoEntry?, in entries: [LocalVideoEntry]) -> LocalVideoEntry? {
        let waiting = entries.filter { $0.scoreLogId == nil && $0.stage == .failed && !$0.resultSeen }
        if let held, let current = waiting.first(where: { $0.id == held.id }) { return current }
        return waiting.first
    }

    /// Whether it may show: there is one, and a list screen is in front with
    /// no sheet of its own or of HomeView's over it.
    ///
    /// Only on a list screen, not over every screen as androidApp does: iOS
    /// shows one presentation at a time, and an alert presented from the
    /// stack while a pushed screen had a sheet of its own up took that sheet
    /// down to show itself (measured on the simulator: task-12-report.md, fix
    /// round 1). A pushed screen's sheets are not known here; a list screen's
    /// are. On a list the scoring board is never in front, so Ruling 15's wait
    /// for the board holds too.
    static func shows(hasEntry: Bool, listInFront: Bool, sheetOpen: Bool) -> Bool {
        hasEntry && listInFront && !sheetOpen
    }
}

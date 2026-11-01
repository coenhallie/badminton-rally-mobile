import Foundation

/// A screen the signed-in stack pushes over Players, its root. HomeView keeps
/// them in one path (`NavigationStack(path:)`), not in a binding per screen.
///
/// One path because two root-declared `navigationDestination` bindings do not
/// stack: with All matches on screen, pushing a match through a second binding
/// had SwiftUI clear the first one itself, so Back from that match landed on
/// Players (measured on the simulator, task-12-report.md). A path holds a
/// match over All matches as two entries, and every move this file names is a
/// single reassignment of it, which is the transition SwiftUI settles
/// reliably: a pop on one binding racing a push on another left a destination
/// blank on-device (see task-13-report.md's create-and-finish investigation).
///
/// The match page's own pushes (a rally, court marking, the board) stay on the
/// match page as `navigationDestination`s of its own, outside this path.
enum HomeRoute: Hashable {
    /// The match list, every match, assigned to a player or not.
    case allMatches
    case match(MatchRoute)
    case courtMarking(CourtMarkingRoute)
    case settings
    case newMatch
    /// The create-and-finish flow's first stage: the board, straight from
    /// creating a match, with no match page under it.
    case scoring(scoreLogId: String)
    /// Its second stage: the match page the finished board lands on, in the
    /// board's place. The chosen intent (if any) rides along so the page can
    /// act on it once.
    case finished(scoreLogId: String, attach: AttachIntent?)
}

/// The rules HomeView's path follows, apart from the view so they are tested
/// (`HomeRouteTests`).
enum HomePath {
    /// Whether a list screen is in front: Players (nothing pushed) or All
    /// matches on top. The finished-run watcher announces only then, and the
    /// root toast is drawn only there.
    static func listInFront(_ path: [HomeRoute]) -> Bool {
        path.last == nil || path.last == .allMatches
    }

    /// Where the background-work indicator goes (Ruling 13): All matches over
    /// Players, with nothing above it. Back to the All matches already on the
    /// stack when there is one, so its filter, sort and scroll survive; a
    /// fresh one otherwise. Never a second one. Mirrors androidApp's
    /// LocalBackgroundWorkClick in AuthGate.
    static func toAllMatches(_ path: [HomeRoute]) -> [HomeRoute] {
        if let at = path.firstIndex(of: .allMatches) { return Array(path[...at]) }
        return [.allMatches]
    }

    /// `next` in `current`'s place, and nothing above it: New match giving way
    /// to its board, and the board to the match page it finished on. In place
    /// rather than a pop and a push, so the screen under it never shows. When
    /// `current` is not on the path any more, `next` is pushed on top.
    static func replacing(_ current: HomeRoute, with next: HomeRoute, in path: [HomeRoute]) -> [HomeRoute] {
        guard let at = path.lastIndex(of: current) else { return path + [next] }
        return Array(path[..<at]) + [next]
    }
}

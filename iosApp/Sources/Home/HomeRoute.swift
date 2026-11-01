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
/// blank on-device (see commit 623ecddb's create-and-finish investigation).
///
/// The match page's own pushes (a rally, court marking, the board) stay on the
/// match page as `navigationDestination`s of its own, outside this path.
enum HomeRoute: Hashable {
    /// The match list, every match, assigned to a player or not.
    case allMatches
    /// One player's page, over Players. Never more than one on the path: the
    /// page's switcher replaces it (`HomePath.toPlayer`).
    case player(id: String)
    case match(MatchRoute)
    case courtMarking(CourtMarkingRoute)
    case settings
    /// The new match form; `playerId` is the player it is started for (their
    /// page's Add match), who will be at home, or nil from Players.
    case newMatch(playerId: String?)
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
    /// Whether a list screen is in front: Players (nothing pushed), All
    /// matches or a player's page on top. The finished-run watcher announces
    /// only then, and the root toast is drawn only there. Mirrors androidApp's
    /// AuthGate, whose finished-run watcher counts the same three.
    static func listInFront(_ path: [HomeRoute]) -> Bool {
        switch path.last {
        case nil, .allMatches, .player: return true
        default: return false
        }
    }

    /// Where the background-work indicator goes (Ruling 13): All matches over
    /// Players, with nothing above it. Back to the All matches already on the
    /// stack when there is one, so its filter, sort and scroll survive; a
    /// fresh one otherwise. Never a second one. Mirrors androidApp's
    /// LocalBackgroundWorkClick in AuthGate.
    ///
    /// All matches is only ever pushed from Players, so when it is on the path
    /// it is the path's first entry, and keeping what is below it keeps
    /// nothing: a player's page is never under it, and goes with whatever was
    /// over it when the indicator is tapped from there.
    static func toAllMatches(_ path: [HomeRoute]) -> [HomeRoute] {
        if let at = path.firstIndex(of: .allMatches) { return Array(path[...at]) }
        return [.allMatches]
    }

    /// The page for `id`, in place of the player's page already on the path
    /// (and everything above it), or pushed when there is none. The switcher
    /// on a player's page swaps players this way, so Back goes to Players and
    /// not to the player switched away from; a second tap on a Players row
    /// mid-push lands on the same one page. Mirrors androidApp's
    /// `onSwitchPlayer` (`popUpTo<Route.Player> { inclusive = true }`).
    static func toPlayer(_ id: String, in path: [HomeRoute]) -> [HomeRoute] {
        guard let at = path.lastIndex(where: { if case .player = $0 { true } else { false } }) else {
            return path + [.player(id: id)]
        }
        return Array(path[..<at]) + [.player(id: id)]
    }

    /// Back to Players once the player `id` has gone (deleted here or on
    /// another device), but only while their page is the one in front: a page
    /// under a match must not pop the match from under the coach, and closes
    /// once the coach is back on it. Mirrors androidApp's `onGone`, which is
    /// held until the page is resumed.
    static func closingPlayer(_ id: String, in path: [HomeRoute]) -> [HomeRoute] {
        guard path.last == .player(id: id) else { return path }
        return []
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
